package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFragmentShadingRateFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFragmentShadingRatePropertiesKHR;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_PROPERTIES_EXT;
import static org.lwjgl.vulkan.NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT;
import static org.lwjgl.vulkan.NVRayTracingInvocationReorder.VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.NVRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_NV;
import static org.lwjgl.vulkan.KHRFragmentShadingRate.VK_KHR_FRAGMENT_SHADING_RATE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRFragmentShadingRate.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRFragmentShadingRate.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_PROPERTIES_KHR;
import static org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;

/**
 * RT device bring-up. Enables the hardware ray-tracing device extensions and their
 * feature structs on vanilla's Blaze3D device at {@code vkCreateDevice} time.
 *
 * <p>Vanilla assembles a {@code VkPhysicalDeviceFeatures2} pNext chain from the
 * {@code Set<VulkanFeature>} (arg2) via {@code VulkanFeature.set} →
 * {@code findOrCreateStructInPNextChain} (dedup by sType), so {@code bufferDeviceAddress}
 * merges into the existing {@code VkPhysicalDeviceVulkan12Features} struct and the two
 * KHR structs are created fresh. BDA / descriptor-indexing / SPIR-V 1.4 are core on the
 * 1.4 device, so only three extension <i>names</i> are needed; the rest are feature enables.
 *
 * <p>Extension names are added to the device extension list separately; feature structs are added here.
 * Both are gated on the selected device actually supporting RT; if not, nothing is added
 * and the device comes up exactly as vanilla. {@code caustica.rt} is read once here, at
 * {@code vkCreateDevice} time, before the device exists — flipping it later at runtime cannot add
 * device features to an already-created device, so a config change only takes effect on restart.
 */
public final class RtDeviceBringup {
    public static boolean enabledByProperty() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    /**
     * The device extensions RT needs (BDA/descriptor-indexing/SPIR-V 1.4 are core on 1.4).
     * {@code ray_tracing_position_fetch} lets the closest-hit read hit triangle vertex positions
     * ({@code gl_HitTriangleVertexPositionsEXT}) for the normal-map TBN, avoiding a positions buffer
     * plumbed through the geometry tables. Supported on NVIDIA RTX and AMD RDNA 3+ (Mesa 26+).
     * {@code ray_query} lets fragment shaders (the world-overlay pass, e.g. block outline) issue inline
     * {@code rayQueryEXT} occlusion tests against the same TLAS the ray-tracing pipeline traces, without a
     * dedicated raygen dispatch.
     */
    public static final List<String> RT_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME);

    /**
     * Shader Execution Reordering — a scheduling optimisation for the world raygen, NOT a hard requirement.
     * The SPIR-V extension differs between the original NVIDIA path and the ratified EXT path; prefer EXT when
     * present, else NV for older NVIDIA drivers. When a device exposes neither (AMD RDNA, Intel Arc, older
     * NVIDIA), {@link SerBackend#NONE} is selected and the {@code world_noser.rgen.spv} variant — which traces
     * with plain {@code traceRayEXT} instead of a hit object — is used, so RT still comes up. SER is therefore
     * treated like the other optional extensions below, never gating {@link #firstUnsupported}.
     */
    public static final List<String> SER_EXTENSIONS = List.of(
            VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME,
            VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);

    /**
     * OPTIONAL RT extensions: enabled only when the selected device supports them AND the gate is on, but
     * never required — a device lacking them still comes up RT-capable (unlike {@link #RT_EXTENSIONS}, whose
     * absence disables RT entirely). {@code VK_EXT_opacity_micromap} (any-hit opt, lever C): per-triangle
     * opacity micromaps let the hardware skip {@code world.rahit} on fully-opaque/transparent cutout micro-
     * triangles, so the alpha-test any-hit runs only on the foliage silhouette. Hardware-accelerated on RTX
     * 40-series and Blackwell; absent / software elsewhere, hence optional.
     */
    public static final List<String> OPTIONAL_RT_EXTENSIONS = List.of(
            VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);

    /**
     * Performance extensions: VK_KHR_fragment_shading_rate enables Variable Rate Shading for adaptive
     * sampling based on scene content (sky/flat areas at quarter-rate, high-detail at full-rate).
     * Supported on RDNA2+, RTX 20+, Arc+. Provides 15-30% FPS boost in typical scenes.
     */
    public static final List<String> OPTIONAL_PERF_EXTENSIONS = List.of(
            VK_KHR_FRAGMENT_SHADING_RATE_EXTENSION_NAME);

    /**
     * NVIDIA Reflex. {@code VK_NV_low_latency2} adds no feature bits (function-only extension). Bundled with
     * {@code VK_KHR_present_id}: Reflex's latency markers carry a {@code presentID} that only correlates with
     * a specific present call when that present's {@code vkQueuePresentKHR} chains a matching
     * {@code VkPresentIdKHR} — which needs its own device feature bit (unlike low_latency2, which is
     * function-only).
     */
    public static final List<String> REFLEX_EXTENSIONS = List.of(
            VK_NV_LOW_LATENCY_2_EXTENSION_NAME, VK_KHR_PRESENT_ID_EXTENSION_NAME);

    private static volatile boolean rtRequested;
    private static volatile SerBackend serBackend = SerBackend.NONE;
    private static volatile boolean ommEnabled; // VK_EXT_opacity_micromap actually enabled on the device
    private static volatile boolean vrsEnabled; // VK_KHR_fragment_shading_rate actually enabled on the device
    private static volatile int vrsMinTexelWidth; // minimum shading rate attachment texel width
    private static volatile int vrsMinTexelHeight; // minimum shading rate attachment texel height
    private static volatile int vrsMaxTexelWidth; // maximum shading rate attachment texel width
    private static volatile int vrsMaxTexelHeight; // maximum shading rate attachment texel height
    private static volatile boolean reflexEnabled; // VK_NV_low_latency2 actually enabled on the device
    private static volatile boolean presentIdEnabled; // VK_KHR_present_id actually enabled on the device
    private static volatile boolean wideLinesEnabled; // VkPhysicalDeviceFeatures.wideLines actually enabled
    private static volatile float maxLineWidth = 1.0f; // device's lineWidthRange[1]; 1.0 unless wideLinesEnabled
    private static volatile int overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT; // capped to the device's framebufferColorSampleCounts
    private static volatile int maxOpacity4StateSubdivisionLevel;
    // Async compute — dedicated compute queue for overlapping denoise work
    private static volatile boolean asyncComputeAvailable; // true if we found a dedicated compute queue
    private static volatile int computeQueueFamilyIndex = -1; // queue family index, or -1 if not available
    private static volatile int computeQueueIndex = 0; // queue index within the family
    /** True when the device's apiVersion is >= VK 1.4 — the baseline the SPIR-V compile target assumes. */
    private static volatile boolean vk14Device;
    /** True when VK_KHR_push_descriptor is enabled; lets us bind the RT frame's per-frame descriptor set
     *  inline (vkCmdPushDescriptorSetKHR) instead of allocating a descriptor set + binding. Win on the
     *  hot path where tlas/outImage/guide buffers change every frame. Core on VK 1.4; otherwise we need
     *  the KHR_push_descriptor extension explicitly. */
    private static volatile boolean pushDescriptorEnabled;
    private static boolean loggedUnavailable;

    private enum SerBackend {
        NONE("none", null, "world_noser.rgen.spv"),
        NV("NV", VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME, "world_nv.rgen.spv"),
        EXT("EXT", VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME, "world.rgen.spv");

        final String label;
        final String extensionName;
        final String worldRaygenShader;

        SerBackend(String label, String extensionName, String worldRaygenShader) {
            this.label = label;
            this.extensionName = extensionName;
            this.worldRaygenShader = worldRaygenShader;
        }
    }

    private RtDeviceBringup() {
    }

    /** True once we have augmented a device creation to request RT (extensions + features). */
    public static boolean rtRequested() {
        return rtRequested;
    }

    public static String worldRaygenShader() {
        return serBackend.worldRaygenShader;
    }

    public static boolean serNvEnabled() {
        return serBackend == SerBackend.NV;
    }

    public static boolean serExtEnabled() {
        return serBackend == SerBackend.EXT;
    }

    /** True if {@code VK_EXT_opacity_micromap} was enabled on the device (gate on + device support). */
    public static boolean ommEnabled() {
        return ommEnabled;
    }

    /** True if {@code VK_KHR_fragment_shading_rate} was enabled on the device (automatic, no config gate).
     *  Enables Variable Rate Shading for adaptive sampling based on scene content. */
    public static boolean vrsEnabled() {
        return vrsEnabled;
    }

    /** Minimum shading rate attachment texel size (width). Typical: 8 or 16. */
    public static int vrsMinTexelWidth() {
        return vrsMinTexelWidth;
    }

    /** Minimum shading rate attachment texel size (height). Typical: 8 or 16. */
    public static int vrsMinTexelHeight() {
        return vrsMinTexelHeight;
    }

    /** Maximum shading rate attachment texel size (width). */
    public static int vrsMaxTexelWidth() {
        return vrsMaxTexelWidth;
    }

    /** Maximum shading rate attachment texel size (height). */
    public static int vrsMaxTexelHeight() {
        return vrsMaxTexelHeight;
    }

    /** True if async compute is available (dedicated compute queue for overlapping denoise). */
    public static boolean asyncComputeAvailable() {
        return asyncComputeAvailable;
    }

    /** Compute queue family index (-1 if not available). */
    public static int computeQueueFamilyIndex() {
        return computeQueueFamilyIndex;
    }

    /** Compute queue index within the family. */
    public static int computeQueueIndex() {
        return computeQueueIndex;
    }

    /** True if {@code VK_NV_low_latency2} (Reflex) was enabled on the device (gate on + device support). */
    public static boolean reflexEnabled() {
        return reflexEnabled;
    }

    /** True if {@code VK_KHR_present_id} was enabled on the device (needed for Reflex marker/present correlation). */
    public static boolean presentIdEnabled() {
        return presentIdEnabled;
    }

    /** Hardware limit for 4-state opacity micromaps, populated by {@link #probe(VkDevice)}. */
    public static int maxOpacity4StateSubdivisionLevel() {
        return maxOpacity4StateSubdivisionLevel;
    }

    /** True if {@code VkPhysicalDeviceFeatures.wideLines} was enabled on the device (world-overlay thick
     *  lines, e.g. the block outline, use this instead of a screen-space quad when available). */
    public static boolean wideLinesEnabled() {
        return wideLinesEnabled;
    }

    /** True if the device reports {@code apiVersion >= VK 1.4}. SPIR-V compile target is {@code vulkan1.4}
     *  so we refuse RT on older devices — BDA / descriptor-indexing / SPIR-V 1.4 features are core on
     *  1.4 but KHR-only on older, and bringing the KHR extensions in adds duplication for no benefit. */
    public static boolean vk14Device() {
        return vk14Device;
    }

    /** True if {@code VK_KHR_push_descriptor} is enabled on the device (core on VK 1.4). When true, the
     *  RT composite path uses {@code vkCmdPushDescriptorSetKHR} for the per-frame TLAS / output / guide
     *  bindings, eliminating one descriptor set allocation + bind per frame. */
    public static boolean pushDescriptorEnabled() {
        return pushDescriptorEnabled;
    }

    /** The device's max native line width (raster {@code lineWidthRange[1]}); 1.0 if wideLines isn't
     *  enabled (Vulkan mandates exactly 1.0 in that case). Callers must clamp their desired width to this. */
    public static float maxLineWidth() {
        return maxLineWidth;
    }

    /** {@code VK_SAMPLE_COUNT_4_BIT} capped down to whatever the device's {@code framebufferColorSampleCounts}
     *  actually advertises (2x, or 1x/no MSAA on the rare device that lacks even that) — no device feature to
     *  enable, just a raster/framebuffer property, unlike {@link #wideLinesEnabled()}. World-overlay passes
     *  that need edge AA (e.g. the block outline's native wide line) use this as their pipeline's
     *  {@code rasterizationSamples}. */
    public static int overlayMsaaSamples() {
        return overlayMsaaSamples;
    }

    /** Optional extensions the gate wants AND the device supports — added but never required. */
    private static List<String> supportedOptionalExtensions(VulkanPhysicalDevice physicalDevice) {
        List<String> supported = new ArrayList<>();
        if (ommRequested()) {
            OPTIONAL_RT_EXTENSIONS.stream().filter(physicalDevice::hasDeviceExtension).forEach(supported::add);
        }
        // Performance extensions (VRS): always add when supported, no config gate
        OPTIONAL_PERF_EXTENSIONS.stream().filter(physicalDevice::hasDeviceExtension).forEach(supported::add);
        if (reflexRequested()) {
            REFLEX_EXTENSIONS.stream().filter(physicalDevice::hasDeviceExtension).forEach(supported::add);
        }
        return supported;
    }

    private static boolean ommRequested() {
        return CausticaConfig.Rt.Omm.ENABLED.value();
    }

    private static boolean reflexRequested() {
        return CausticaConfig.Rt.Reflex.ENABLED.value();
    }

    /** Query the raw {@code VkPhysicalDeviceFeatures} for {@code wideLines} support — no wrapper on
     *  {@code VulkanPhysicalDevice} exposes this, so it's fetched directly off the raw handle, same as
     *  {@link #probe} already does for other physical-device queries. */
    private static boolean supportsWideLines(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            VK10.vkGetPhysicalDeviceFeatures(physicalDevice.vkPhysicalDevice(), features);
            return features.wideLines();
        }
    }

    private static SerBackend selectSerBackend(VulkanPhysicalDevice physicalDevice) {
        if (physicalDevice.hasDeviceExtension(VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME)) {
            return SerBackend.EXT;
        }
        if (physicalDevice.hasDeviceExtension(VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME)) {
            return SerBackend.NV;
        }
        return SerBackend.NONE;
    }

    private static String firstUnsupported(VulkanPhysicalDevice physicalDevice) {
        // VK 1.4 baseline: BDA / descriptor-indexing / SPIR-V 1.4 are core. Older drivers expose them
        // as KHR extensions; requiring both forms doubled the extension list and the feature struct
        // wiring for zero benefit. The compile target is vulkan1.4 too — older apiVersions would
        // mismatch what glslangValidator produced and the validation layer would flag mismatched
        // SPIR-V / API versions on the pipeline creation.
        int apiVersion = physicalDevice.vkPhysicalDeviceProperties().apiVersion();
        if (apiVersion < VK_API_VERSION_1_4) {
            return "Vulkan 1.4 (got 0x" + Integer.toHexString(apiVersion) + ", need 0x" + Integer.toHexString(VK_API_VERSION_1_4) + ")";
        }
        for (String ext : RT_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(ext)) {
                return ext;
            }
        }
        // SER is intentionally NOT checked here: a device without it still comes up RT-capable via the
        // no-SER raygen variant (see SER_EXTENSIONS / SerBackend.NONE). Only RT_EXTENSIONS are mandatory.
        return null;
    }

    /** Standalone path: add RT extension names to the (mutable) arg0 list. */
    public static void addExtensions(List<String> augmentedExtensions, VulkanPhysicalDevice physicalDevice) {
        if (!enabledByProperty() || firstUnsupported(physicalDevice) != null) {
            return;
        }
        for (String ext : RT_EXTENSIONS) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
        String serExtension = selectSerBackend(physicalDevice).extensionName;
        if (serExtension != null && !augmentedExtensions.contains(serExtension)) {
            augmentedExtensions.add(serExtension);
        }
        for (String ext : supportedOptionalExtensions(physicalDevice)) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
    }

    /** Add the RT VulkanFeatures to arg2 after the matching extension names have been requested. */
    @SuppressWarnings("unchecked")
    public static void addFeatures(Args args, VulkanPhysicalDevice physicalDevice) {
        if (!enabledByProperty()) {
            return;
        }
        serBackend = SerBackend.NONE;
        pushDescriptorEnabled = false;
        String missing = firstUnsupported(physicalDevice);
        if (missing != null) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                CausticaMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks {}", physicalDevice.deviceName(), missing);
            }
            return;
        }
        // VK 1.4 baseline — confirmed by firstUnsupported. Record the device version + push-descriptor
        // availability for the composite path. Push descriptors are core on 1.4; on older drivers the
        // VK_KHR_push_descriptor extension would need an explicit enable, but we refuse those outright.
        vk14Device = true;
        pushDescriptorEnabled = true;

        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        VulkanPNextStruct asStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
        VulkanPNextStruct rtStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);
        VulkanPNextStruct posFetchStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR,
                VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.SIZEOF);
        VulkanPNextStruct rayQueryStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
                VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF);
        SerBackend selectedSerBackend = selectSerBackend(physicalDevice);
        // SER feature struct only when the device actually has an SER extension; SerBackend.NONE (no-SER
        // raygen) enables no reorder feature and comes up RT-capable without it.
        VulkanPNextStruct serStruct = null;
        if (selectedSerBackend == SerBackend.NV) {
            serStruct = new VulkanPNextStruct(
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_NV,
                    VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.SIZEOF);
        } else if (selectedSerBackend == SerBackend.EXT) {
            serStruct = new VulkanPNextStruct(
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT,
                    VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.SIZEOF);
        }
        // bufferDeviceAddress merges into vanilla's existing Vulkan12Features struct.
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS));
        // Bindless entity textures: a runtime-sized sampler2D[] indexed non-uniformly in the hit shader,
        // with partially-bound + update-after-bind slots (a growing per-RenderType registry). Core on the
        // VK 1.4 device; just needs enabling alongside bufferDeviceAddress on the same struct.
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "runtimeDescriptorArray",
                VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "shaderSampledImageArrayNonUniformIndexing",
                VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingPartiallyBound",
                VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingSampledImageUpdateAfterBind",
                VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND));
        // shaderInt64: the world hit shader uses uint64_t buffer-reference addresses (Int64 capability).
        features.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64",
                VkPhysicalDeviceFeatures.SHADERINT64));
        features.add(new VulkanFeature(asStruct, "accelerationStructure",
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE));
        features.add(new VulkanFeature(rtStruct, "rayTracingPipeline",
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE));
        features.add(new VulkanFeature(posFetchStruct, "rayTracingPositionFetch",
                VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.RAYTRACINGPOSITIONFETCH));
        features.add(new VulkanFeature(rayQueryStruct, "rayQuery",
                VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY));
        if (serStruct != null) {
            features.add(new VulkanFeature(serStruct, "rayTracingInvocationReorder",
                    selectedSerBackend == SerBackend.NV
                            ? VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.RAYTRACINGINVOCATIONREORDER
                            : VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.RAYTRACINGINVOCATIONREORDER));
        }

        // Optional: wideLines (core VK10 feature, no extension). Lets the world-overlay pass (block
        // outline) draw a real thick native line via a raster pipeline's lineWidth / VK_DYNAMIC_STATE_LINE
        // _WIDTH instead of a screen-space quad. Its absence must not disable RT — the overlay falls back
        // to whatever the device's mandated lineWidth (1.0) allows.
        wideLinesEnabled = supportsWideLines(physicalDevice);
        if (wideLinesEnabled) {
            features.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "wideLines",
                    VkPhysicalDeviceFeatures.WIDELINES));
            maxLineWidth = physicalDevice.vkPhysicalDeviceProperties().limits().lineWidthRange(1);
        } else {
            maxLineWidth = 1.0f;
        }

        // World-overlay MSAA (block outline edge AA): a framebuffer property, not a feature — no
        // VulkanFeature entry needed, just cap the desired 4x down to what the device actually supports.
        int colorSampleCounts = physicalDevice.vkPhysicalDeviceProperties().limits().framebufferColorSampleCounts();
        if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_4_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_4_BIT;
        } else if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_2_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_2_BIT;
        } else {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT;
        }

        // Optional: opacity micromaps (any-hit opt). Only when the gate is on AND the device advertises the
        // extension — its absence must not disable RT, so it is kept out of the mandatory feature set above.
        ommEnabled = ommRequested() && physicalDevice.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        if (ommEnabled) {
            VulkanPNextStruct ommStruct = new VulkanPNextStruct(
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
                    VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF);
            features.add(new VulkanFeature(ommStruct, "micromap",
                    VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP));
        }

        // Optional: Variable Rate Shading (VK_KHR_fragment_shading_rate). Always enable when the device
        // advertises it (no config gate). Enables adaptive sampling: sky/flat areas at quarter-rate (4x4),
        // high-detail areas at full-rate (1x1). Typical gain: +15-30% FPS. Supported on RDNA2+, RTX 20+, Arc+.
        vrsEnabled = physicalDevice.hasDeviceExtension(VK_KHR_FRAGMENT_SHADING_RATE_EXTENSION_NAME);
        if (vrsEnabled) {
            VulkanPNextStruct vrsStruct = new VulkanPNextStruct(
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_FEATURES_KHR,
                    VkPhysicalDeviceFragmentShadingRateFeaturesKHR.SIZEOF);
            features.add(new VulkanFeature(vrsStruct, "pipelineFragmentShadingRate",
                    VkPhysicalDeviceFragmentShadingRateFeaturesKHR.PIPELINEFRAGMENTSHADINGRATE));
            features.add(new VulkanFeature(vrsStruct, "attachmentFragmentShadingRate",
                    VkPhysicalDeviceFragmentShadingRateFeaturesKHR.ATTACHMENTFRAGMENTSHADINGRATE));
        }

        // Optional: NVIDIA Reflex (VK_NV_low_latency2). Function-only extension, no feature struct to add.
        reflexEnabled = reflexRequested() && physicalDevice.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);

        // Optional: VK_KHR_present_id (presentID<->present correlation for Reflex markers). Its absence must
        // not disable Reflex sleep/pacing itself — only marker correlation degrades.
        presentIdEnabled = reflexEnabled && physicalDevice.hasDeviceExtension(VK_KHR_PRESENT_ID_EXTENSION_NAME);
        if (presentIdEnabled) {
            VulkanPNextStruct presentIdStruct = new VulkanPNextStruct(
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR,
                    VkPhysicalDevicePresentIdFeaturesKHR.SIZEOF);
            features.add(new VulkanFeature(presentIdStruct, "presentId",
                    VkPhysicalDevicePresentIdFeaturesKHR.PRESENTID));
        }

        args.set(2, features);

        rtRequested = true;
        serBackend = selectedSerBackend;
        CausticaMod.LOGGER.info(
                "Ray tracing: enabling {} + {}{}{} + features [bufferDeviceAddress, accelerationStructure, rayTracingPipeline, rayQuery, rayTracingInvocationReorder({}), pushDescriptor"
                        + (wideLinesEnabled ? ", wideLines(max=" + maxLineWidth + ")" : "")
                        + (ommEnabled ? ", opacityMicromap" : "") + "] + overlayMsaa=" + overlayMsaaSamples + "x on VK1.4 [{}]",
                RT_EXTENSIONS, serBackend.extensionName != null ? serBackend.extensionName : "no SER extension",
                ommEnabled ? " + " + OPTIONAL_RT_EXTENSIONS : "",
                reflexEnabled ? " + " + REFLEX_EXTENSIONS : "", serBackend.label, physicalDevice.deviceName());
    }

    /**
     * Post-creation verification: confirm the RT entry points actually loaded on the new
     * device and log the RT pipeline / acceleration-structure limits. If this logs "OK",
     * the device truly came up RT-capable.
     */
    public static void probe(VkDevice device) {
        if (!rtRequested) {
            CausticaMod.LOGGER.info("Ray tracing not requested; skipping RT probe");
            maxOpacity4StateSubdivisionLevel = 0;
            vrsMinTexelWidth = vrsMinTexelHeight = vrsMaxTexelWidth = vrsMaxTexelHeight = 0;
            vk14Device = false;
            pushDescriptorEnabled = false;
            return;
        }
        try {
            VKCapabilitiesDevice caps = device.getCapabilities();
            boolean rtPipeline = caps.vkCreateRayTracingPipelinesKHR != 0L;
            boolean asBuild = caps.vkCmdBuildAccelerationStructuresKHR != 0L;
            boolean traceRays = caps.vkCmdTraceRaysKHR != 0L;
            if (!(rtPipeline && asBuild && traceRays)) {
                CausticaMod.LOGGER.error(
                        "RT extensions enabled but entry points missing (rtPipeline={}, asBuild={}, traceRays={}) — RT bring-up FAILED",
                        rtPipeline, asBuild, traceRays);
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                        VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                        VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
                rtProps.pNext(asProps.address());
                // Chain the OMM properties only when the feature is enabled (else the driver would ignore an
                // unrecognized struct, but keeping the chain clean matches the enabled feature set).
                VkPhysicalDeviceOpacityMicromapPropertiesEXT ommProps = null;
                if (ommEnabled) {
                    ommProps = VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
                    asProps.pNext(ommProps.address());
                }
                VkPhysicalDeviceFragmentShadingRatePropertiesKHR vrsProps = null;
                if (vrsEnabled) {
                    vrsProps = VkPhysicalDeviceFragmentShadingRatePropertiesKHR.calloc(stack).sType$Default();
                    if (ommProps != null) {
                        ommProps.pNext(vrsProps.address());
                    } else {
                        asProps.pNext(vrsProps.address());
                    }
                }
                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                props2.pNext(rtProps.address());
                VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props2);

                CausticaMod.LOGGER.info(
                        "RT bring-up OK — shaderGroupHandleSize={}, shaderGroupBaseAlignment={}, maxRayRecursionDepth={}; "
                                + "maxAS geometry/instance/primitive = {}/{}/{}",
                        rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(), rtProps.maxRayRecursionDepth(),
                        asProps.maxGeometryCount(), asProps.maxInstanceCount(), asProps.maxPrimitiveCount());
                if (ommProps != null) {
                    maxOpacity4StateSubdivisionLevel = ommProps.maxOpacity4StateSubdivisionLevel();
                    CausticaMod.LOGGER.info(
                            "Opacity micromaps enabled — maxSubdivisionLevel 4-state={}, 2-state={}",
                            ommProps.maxOpacity4StateSubdivisionLevel(), ommProps.maxOpacity2StateSubdivisionLevel());
                } else {
                    maxOpacity4StateSubdivisionLevel = 0;
                }
                if (vrsProps != null) {
                    vrsMinTexelWidth = vrsProps.minFragmentShadingRateAttachmentTexelSize().width();
                    vrsMinTexelHeight = vrsProps.minFragmentShadingRateAttachmentTexelSize().height();
                    vrsMaxTexelWidth = vrsProps.maxFragmentShadingRateAttachmentTexelSize().width();
                    vrsMaxTexelHeight = vrsProps.maxFragmentShadingRateAttachmentTexelSize().height();
                    CausticaMod.LOGGER.info(
                            "Variable Rate Shading enabled — texelSize min={}x{}, max={}x{}",
                            vrsMinTexelWidth, vrsMinTexelHeight, vrsMaxTexelWidth, vrsMaxTexelHeight);
                } else {
                    vrsMinTexelWidth = vrsMinTexelHeight = vrsMaxTexelWidth = vrsMaxTexelHeight = 0;
                }
            }
            if (reflexEnabled) {
                boolean sleepMode = caps.vkSetLatencySleepModeNV != 0L;
                boolean sleep = caps.vkLatencySleepNV != 0L;
                boolean marker = caps.vkSetLatencyMarkerNV != 0L;
                boolean timings = caps.vkGetLatencyTimingsNV != 0L;
                if (sleepMode && sleep && marker && timings) {
                    CausticaMod.LOGGER.info(
                            "Reflex (VK_NV_low_latency2) entry points loaded — presentId={}", presentIdEnabled);
                } else {
                    CausticaMod.LOGGER.error(
                            "Reflex extension enabled but entry points missing (sleepMode={}, sleep={}, marker={}, timings={})",
                            sleepMode, sleep, marker, timings);
                    reflexEnabled = false;
                }
            }

            // Async compute — probe for dedicated compute queue
            probeAsyncCompute(device);

        } catch (Throwable t) {
            // A probe must never break device creation.
            CausticaMod.LOGGER.error("RT probe threw; continuing without RT", t);
        }
    }

    /**
     * Probe for a dedicated compute queue (different family from graphics) for async compute.
     * AMD RDNA2+ typically has dedicated compute queues; NVIDIA uses the same queue family.
     */
    private static void probeAsyncCompute(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice physicalDevice = device.getPhysicalDevice();

            // Get queue family properties
            IntBuffer pCount = stack.mallocInt(1);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, null);
            int queueFamilyCount = pCount.get(0);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount, stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, queueFamilies);

            // Strategy 1: Find pure compute queue (compute but NOT graphics)
            for (int i = 0; i < queueFamilyCount; i++) {
                VkQueueFamilyProperties props = queueFamilies.get(i);
                int flags = props.queueFlags();
                boolean hasCompute = (flags & VK10.VK_QUEUE_COMPUTE_BIT) != 0;
                boolean hasGraphics = (flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0;

                if (hasCompute && !hasGraphics && props.queueCount() > 0) {
                    // Found pure compute queue
                    asyncComputeAvailable = true;
                    computeQueueFamilyIndex = i;
                    computeQueueIndex = 0;
                    CausticaMod.LOGGER.info(
                            "Async Compute available — dedicated compute queue family {} (pure compute, {} queues)",
                            i, props.queueCount());
                    return;
                }
            }

            // Strategy 2: Find compute queue in different family than graphics (assume graphics is family 0)
            // Note: Graphics queue is typically family 0, but we can't verify at probe time
            int assumedGraphicsFamily = 0;
            for (int i = 1; i < queueFamilyCount; i++) { // Skip family 0 (assumed graphics)
                VkQueueFamilyProperties props = queueFamilies.get(i);
                int flags = props.queueFlags();
                boolean hasCompute = (flags & VK10.VK_QUEUE_COMPUTE_BIT) != 0;

                if (hasCompute && props.queueCount() > 0) {
                    // Found compute queue in different family
                    asyncComputeAvailable = true;
                    computeQueueFamilyIndex = i;
                    computeQueueIndex = 0;
                    CausticaMod.LOGGER.info(
                            "Async Compute available — compute queue family {} (separate from assumed graphics family {}, {} queues)",
                            i, assumedGraphicsFamily, props.queueCount());
                    return;
                }
            }

            // No dedicated compute queue found
            asyncComputeAvailable = false;
            computeQueueFamilyIndex = -1;
            CausticaMod.LOGGER.info("Async Compute not available — no dedicated compute queue (will use single-queue fallback)");

        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Failed to probe async compute queues", t);
            asyncComputeAvailable = false;
            computeQueueFamilyIndex = -1;
        }
    }
}
