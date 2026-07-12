package dev.comfyfluffy.caustica.vendor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU vendor + architecture detection from {@code VkPhysicalDeviceProperties}.
 *
 * <p>Caustica adapts its upscaler / frame-gen / RT optimization choice to the underlying GPU. Detection is
 * vendor-id + device-id based, with RDNA-generation inference from the marketing name (the {@code deviceName}
 * field — RDNA 1/2/3/4 are not exposed in {@code VkPhysicalDeviceProperties} as a structured field, and
 * AMD's vendor JSON has not been stable across driver releases). When detection is ambiguous (the device
 * name does not match a known RDNA token), the safest fallback is {@code AMD_RDNA2} — the lowest RDNA
 * version that supports every AMD feature Caustica gates on.
 */
public final class GpuVendor {
    /** PCI vendor id for AMD. */
    public static final int VENDOR_AMD = 0x1002;
    /** PCI vendor id for NVIDIA. */
    public static final int VENDOR_NVIDIA = 0x10DE;
    /** PCI vendor id for Intel. */
    public static final int VENDOR_INTEL = 0x8086;
    /** PCI vendor id for Microsoft (WARP). */
    public static final int VENDOR_MICROSOFT = 0x1414;

    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

    public enum Vendor {
        NVIDIA("nvidia"),
        AMD("amd"),
        INTEL("intel"),
        OTHER("other");

        final String key;
        Vendor(String key) { this.key = key; }

        public String key() { return key; }
    }

    public enum AmdArch {
        // RDNA 1 (RX 5000) — has hardware RT but is the lowest tier Caustica supports.
        RDNA1("rdna1"),
        // RDNA 2 (RX 6000) — has RT units, no AI accelerator, no INT8 dot product unit at full rate.
        RDNA2("rdna2"),
        // RDNA 3 (RX 7000) — has RT units, AI accelerator, INT8 (no FP8). FSR 4.1 INT8 supported.
        RDNA3("rdna3"),
        // RDNA 4 (RX 9000) — has RT units, second-gen AI accelerator, FP8 + INT8. FSR 4 native.
        RDNA4("rdna4"),
        // Older than RDNA (GCN / pre-GCN) — not a Caustica target.
        PRE_RDNA("pre-rdna"),
        UNKNOWN("unknown");

        final String key;
        AmdArch(String key) { this.key = key; }

        public String key() { return key; }
    }

    public enum IntelArch {
        // Xe-LP / Xe-HPG (Arc Alchemist) — XMX present.
        ARC("arc"),
        // Xe-LPG (Meteor Lake iGPU) — XMX-Lite / DPAS, not full XMX.
        XE_LPG("xe-lpg"),
        // Xe2 / Xe-LPG+ (Lunar Lake / Arrow Lake iGPU).
        XE2("xe2"),
        UNKNOWN("unknown");

        final String key;
        IntelArch(String key) { this.key = key; }

        public String key() { return key; }
    }

    public final Vendor vendor;
    /** Non-null only when {@link #vendor} is {@link Vendor#AMD}. */
    public final AmdArch amdArch;
    /** Non-null only when {@link #vendor} is {@link Vendor#INTEL}. */
    public final IntelArch intelArch;
    public final String deviceName;
    public final int vendorId;
    public final int deviceId;

    private GpuVendor(Vendor vendor, AmdArch amdArch, IntelArch intelArch,
                      String deviceName, int vendorId, int deviceId) {
        this.vendor = vendor;
        this.amdArch = amdArch;
        this.intelArch = intelArch;
        this.deviceName = deviceName;
        this.vendorId = vendorId;
        this.deviceId = deviceId;
    }

    /**
     * Probe the physical device via {@code vkGetPhysicalDeviceProperties}. Safe to call repeatedly; the
     * device properties don't change after device creation. Reads vendor-id + device-id + deviceName
     * straight from the Vulkan struct.
     */
    public static GpuVendor detect() {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return new GpuVendor(Vendor.OTHER, null, null, "non-vulkan", 0, 0);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), props);
            int vendorId = props.vendorID();
            int deviceId = props.deviceID();
            String name = props.deviceNameString();
            return detectInternal(vendorId, deviceId, name);
        } catch (Throwable t) {
            LOGGER.warn("GpuVendor.detect failed; assuming OTHER", t);
            return new GpuVendor(Vendor.OTHER, null, null, "unknown", 0, 0);
        }
    }

    /**
     * Lower-level entry point: detect from already-cached properties. Used by {@link #detect()} and by
     * tests / callers that have the properties in hand.
     */
    public static GpuVendor detect(VkPhysicalDeviceProperties props) {
        return detectInternal(props.vendorID(), props.deviceID(), props.deviceNameString());
    }

    private static GpuVendor detectInternal(int vendorId, int deviceId, String name) {
        Vendor v;
        AmdArch amd = null;
        IntelArch intel = null;
        switch (vendorId) {
            case VENDOR_NVIDIA -> v = Vendor.NVIDIA;
            case VENDOR_AMD -> {
                v = Vendor.AMD;
                amd = inferAmdArch(name);
            }
            case VENDOR_INTEL -> {
                v = Vendor.INTEL;
                intel = inferIntelArch(name);
            }
            default -> v = Vendor.OTHER;
        }
        GpuVendor detected = new GpuVendor(v, amd, intel, name, vendorId, deviceId);
        LOGGER.info(String.format("GPU vendor detected: vendor=%s (id=0x%04X) arch=%s deviceName=\"%s\" deviceId=0x%04X",
                v.key, vendorId, archLabel(v, amd, intel), name, deviceId));
        return detected;
    }

    private static AmdArch inferAmdArch(String deviceName) {
        if (deviceName == null) {
            return AmdArch.UNKNOWN;
        }
        // RDNA 4 — RX 9000 series. Marketing name follows "Radeon RX 9xxx" / "Radeon AI PRO R9xxx".
        if (deviceName.contains("RX 9") || deviceName.contains("RX 9")
                || deviceName.contains("AI PRO R9") || deviceName.contains("RX 90")) {
            return AmdArch.RDNA4;
        }
        // RDNA 3 — RX 7000 series. Also covers RDNA 3.5 iGPUs (Strix Halo etc.) which contain "Radeon 8xxM"
        // / "Radeon Graphics" — the iGPU is gated to FSR 3 in practice (no AI accelerator on the
        // RDNA-3.5 die), but we conservatively bucket them with RDNA 3 so the upscaler picker at least
        // considers FSR 4.1 INT8; the actual runtime check in FsrUpscaler verifies the DLL's hardware
        // query and falls back if the iGPU's INT8 rate is insufficient.
        if (deviceName.contains("RX 7") || deviceName.contains("RX 70")
                || deviceName.contains("Radeon 8") || deviceName.contains("Radeon 7")
                || deviceName.contains("Radeon Graphics")) {
            return AmdArch.RDNA3;
        }
        // RDNA 2 — RX 6000 series. FSR 4.1 only lands in 2027 per AMD's published roadmap, so the
        // picker must never auto-select it for RDNA 2.
        if (deviceName.contains("RX 6") || deviceName.contains("RX 60") || deviceName.contains("Radeon Pro W6")) {
            return AmdArch.RDNA2;
        }
        // RDNA 1 — RX 5000 series.
        if (deviceName.contains("RX 5") || deviceName.contains("RX 50") || deviceName.contains("Radeon Pro W5")) {
            return AmdArch.RDNA1;
        }
        return AmdArch.UNKNOWN;
    }

    private static IntelArch inferIntelArch(String deviceName) {
        if (deviceName == null) {
            return IntelArch.UNKNOWN;
        }
        // Arc Alchemist / Battlemage — discrete Arc.
        if (deviceName.contains("Arc ") || deviceName.contains("ARC ")) {
            return IntelArch.ARC;
        }
        // Xe-LPG (Meteor Lake iGPU — "Intel Graphics" or "UHD Graphics" on Ultra 100 series; the Xe-LPG+
        // names Arrow Lake iGPU as "Intel Graphics" too). The XeSS SDK 2.1.1+ supports FG on these via
        // DPAS (XMX-Lite).
        if (deviceName.contains("Intel Graphics") || deviceName.contains("UHD Graphics")) {
            // Xe-LPG vs Xe2 iGPU cannot be told apart from the device name alone on a 2026 driver;
            // XeSS-FG is supported on both as of SDK 2.1.1, so the distinction is mostly informational.
            return IntelArch.XE_LPG;
        }
        // Xe2 discrete (Battlemage / Celestial) — also reported as "Intel Arc" so the above wins, but
        // future-proof in case Intel renames.
        return IntelArch.UNKNOWN;
    }

    private static String archLabel(Vendor v, AmdArch amd, IntelArch intel) {
        if (v == Vendor.AMD && amd != null) {
            return amd.key;
        }
        if (v == Vendor.INTEL && intel != null) {
            return intel.key;
        }
        return v.key;
    }

    /** Whether this GPU can plausibly run FSR 4.1 (RDNA 3 INT8 or RDNA 4 FP8/INT8). */
    public boolean canRunFsr41() {
        return vendor == Vendor.AMD && (amdArch == AmdArch.RDNA3 || amdArch == AmdArch.RDNA4);
    }

    /** Whether this GPU can run FSR 3 / FSR 2 (any modern AMD, also works on NVIDIA / Intel SM 6.4+). */
    public boolean canRunFsr3() {
        if (vendor == Vendor.AMD) {
            return amdArch != AmdArch.PRE_RDNA && amdArch != AmdArch.UNKNOWN;
        }
        // FSR 3 works cross-vendor on SM 6.4+ — NVIDIA Ampere+, RDNA 2+, Arc + Xe-LPG. We have no way
        // to inspect the SM version from Java here; the FSR runtime DLL performs its own device check
        // and the Selector above already filters for "modern enough GPU" via a frame-1 dispatch probe.
        return true;
    }

    /** Whether this GPU can run XeSS (Intel SDK supports DP4a on all SM 6.4+ GPUs from 2.1+). */
    public boolean canRunXeSs() {
        if (vendor == Vendor.INTEL) {
            return true;
        }
        // XeSS 2.1+ also runs on NVIDIA / AMD via DP4a. The runtime DLL probes; this is just a
        // pre-filter so the Selector skips the SDK probe entirely on the wrong vendor when configured.
        return true;
    }

    /** Whether this GPU can run DLSS (NVIDIA only, RTX required for DLSS-RR; FG requires RTX 30+). */
    public boolean canRunDlss() {
        return vendor == Vendor.NVIDIA;
    }
}
