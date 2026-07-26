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
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
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
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRFragmentShadingRate.VK_KHR_FRAGMENT_SHADING_RATE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRFragmentShadingRate.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRFragmentShadingRate.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_PROPERTIES_KHR;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * RT device bring-up. Enables the hardware ray-tracing device extensions and their
 * feature structs on vanilla's Blaze3D device at {@code vkCreateDevice} time.
 *
 * <p>Vanilla assembles a {@code VkPhysicalDeviceFeatures2} pNext chain from the
 * {@code Set<VulkanFeature>} (arg2) via {@code VulkanFeature.set} →
 * {@code findOrCreateStructInPNextChain} (dedup by sType), so {@code bufferDeviceAddress}
 * merges into the existing {@code VkPhysicalDeviceVulkan12Features} struct and the RT
 * KHR structs are created fresh. BDA / descriptor-indexing / SPIR-V 1.4 are core on the
 * Vulkan 1.2 baseline already required by Blaze3D, so only the RT extension names are
 * needed; the rest are feature enables.
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
     * The minimum device extensions for a KHR ray-tracing pipeline. Buffer device address,
     * descriptor indexing, SPIR-V 1.4, and shader float controls are core in Vulkan 1.2, which
     * is already Blaze3D's baseline. Position fetch and ray query are optional capabilities below.
     */
    public static final List<String> RT_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);

    /**
     * Ratified cross-vendor Shader Execution Reordering extension. This is an optional scheduling
     * optimisation only: devices without {@code VK_EXT_ray_tracing_invocation_reorder} use the
     * {@code world_noser.rgen.spv} variant and remain fully RT-capable.
     */
    public static final List<String> SER_EXTENSIONS = List.of(
            VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);

    /**
     * OPTIONAL RT extensions: position fetch and ray query enable automatically when both the extension and
     * feature bit are available; opacity micromaps additionally require their config gate. None is required —
     * a device lacking them still comes up RT-capable (unlike {@link #RT_EXTENSIONS}, whose absence disables
     * RT entirely). {@code VK_EXT_opacity_micromap} (any-hit opt, lever C): per-triangle
     * opacity micromaps let the hardware skip {@code world.rahit} on fully-opaque/transparent cutout micro-
     * triangles, so the alpha-test any-hit runs only on the foliage silhouette. It remains optional because
     * implementations may omit the extension or provide no useful acceleration for the workload.
     */
    public static final List<String> OPTIONAL_RT_EXTENSIONS = List.of(
            VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME,
            VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);

    /**
     * Performance extensions: VK_KHR_fragment_shading_rate enables Variable Rate Shading for adaptive
     * sampling based on scene content (sky/flat areas at quarter-rate, high-detail at full-rate).
     * Enabled only when the device advertises the standard fragment-shading-rate extension.
     */
    public static final List<String> OPTIONAL_PERF_EXTENSIONS = List.of(
            VK_KHR_FRAGMENT_SHADING_RATE_EXTENSION_NAME);

    private static volatile boolean rtRequested;
    private static volatile SerBackend serBackend = SerBackend.NONE;
    private static volatile boolean positionFetchEnabled; // closest-hit position built-in + BLAS data-access flag
    private static volatile boolean radvDriver; // Mesa RADV — needs several RT workarounds
    private static volatile boolean rayQueryEnabled; // inline TLAS queries in overlay fragment shaders
    private static volatile boolean ommEnabled; // VK_EXT_opacity_micromap actually enabled on the device
    private static volatile boolean vrsEnabled; // VK_KHR_fragment_shading_rate actually enabled on the device
    private static volatile int vrsMinTexelWidth; // minimum shading rate attachment texel width
    private static volatile int vrsMinTexelHeight; // minimum shading rate attachment texel height
    private static volatile int vrsMaxTexelWidth; // maximum shading rate attachment texel width
    private static volatile int vrsMaxTexelHeight; // maximum shading rate attachment texel height
    private static volatile boolean wideLinesEnabled; // VkPhysicalDeviceFeatures.wideLines actually enabled
    private static volatile float maxLineWidth = 1.0f; // device's lineWidthRange[1]; 1.0 unless wideLinesEnabled
    private static volatile int overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT; // capped to the device's framebufferColorSampleCounts
    private static volatile int maxOpacity4StateSubdivisionLevel;
    // Async compute — dedicated compute queue for overlapping denoise work
    private static volatile boolean asyncComputeAvailable; // true if we found a dedicated compute queue
    private static volatile int computeQueueFamilyIndex = -1; // queue family index, or -1 if not available
    private static volatile int computeQueueIndex = 0; // queue index within the family
    private static boolean loggedUnavailable;

    private enum SerBackend {
        NONE("none", null, "world_noser.rgen.spv"),
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

    /** Closest-hit variant selected at device creation. The fallback omits the optional position-fetch
     * capability, uses base-LOD texture sampling, and keeps geometric normals instead of LabPBR _n.
     * RADV bisect: stub (no BDA) and minimal (section+prim only) both survive thousands of frames, so
     * restore full closest-hit shading. any-hit/FSR2 remain isolation-gated elsewhere. */
    public static String worldClosestHitShader() {
        return positionFetchEnabled ? "world.rchit.spv" : "world_noposfetch.rchit.spv";
    }

    public static boolean serExtEnabled() {
        return serBackend == SerBackend.EXT;
    }

    /** True if hit-triangle position fetch was enabled. */
    public static boolean positionFetchEnabled() {
        return positionFetchEnabled;
    }

    /** True when the active device is Mesa RADV (driver workarounds apply). */
    public static boolean isRadv() {
        return radvDriver;
    }

    /** True if inline ray queries were enabled for optional overlay occlusion. */
    public static boolean rayQueryEnabled() {
        return rayQueryEnabled;
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

    /** Hardware limit for 4-state opacity micromaps, populated by {@link #probe(VkDevice)}. */
    public static int maxOpacity4StateSubdivisionLevel() {
        return maxOpacity4StateSubdivisionLevel;
    }

    /** True if {@code VkPhysicalDeviceFeatures.wideLines} was enabled on the device (world-overlay thick
     *  lines, e.g. the block outline, use this instead of a screen-space quad when available). */
    public static boolean wideLinesEnabled() {
        return wideLinesEnabled;
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

    private static VulkanFeature vk10Feature(String name, long fieldOffset) {
        return new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, name, fieldOffset);
    }

    private static VulkanFeature vk12Feature(String name, long fieldOffset) {
        return new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, name, fieldOffset);
    }

    private static VulkanFeature extensionFeature(int sType, int structSize, String name, long fieldOffset) {
        return new VulkanFeature(new VulkanPNextStruct(sType, structSize), name, fieldOffset);
    }

    /** Query one feature bit without requesting it. This keeps optional extensions from turning into
     * {@code vkCreateDevice} failures on drivers that advertise an extension but expose its feature as false. */
    private static boolean supportsFeature(VulkanPhysicalDevice physicalDevice, VulkanFeature feature) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            feature.struct().findOrCreateStructInPNextChain(features, stack);
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features);
            return feature.get(features);
        }
    }

    private static VulkanFeature accelerationStructureFeature() {
        return extensionFeature(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF, "accelerationStructure",
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
    }

    private static VulkanFeature rayTracingPipelineFeature() {
        return extensionFeature(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF, "rayTracingPipeline",
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE);
    }

    private static VulkanFeature positionFetchFeature() {
        return extensionFeature(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR,
                VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.SIZEOF, "rayTracingPositionFetch",
                VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.RAYTRACINGPOSITIONFETCH);
    }

    private static VulkanFeature rayQueryFeature() {
        return extensionFeature(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
                VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF, "rayQuery",
                VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY);
    }

    private static VulkanFeature serFeature() {
        return extensionFeature(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT,
                VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.SIZEOF, "rayTracingInvocationReorder",
                VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.RAYTRACINGINVOCATIONREORDER);
    }

    private static VulkanFeature ommFeature() {
        return extensionFeature(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
                VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF, "micromap",
                VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    }

    private static VulkanFeature pipelineVrsFeature() {
        return extensionFeature(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_FEATURES_KHR,
                VkPhysicalDeviceFragmentShadingRateFeaturesKHR.SIZEOF, "pipelineFragmentShadingRate",
                VkPhysicalDeviceFragmentShadingRateFeaturesKHR.PIPELINEFRAGMENTSHADINGRATE);
    }

    private static VulkanFeature attachmentVrsFeature() {
        return extensionFeature(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADING_RATE_FEATURES_KHR,
                VkPhysicalDeviceFragmentShadingRateFeaturesKHR.SIZEOF, "attachmentFragmentShadingRate",
                VkPhysicalDeviceFragmentShadingRateFeaturesKHR.ATTACHMENTFRAGMENTSHADINGRATE);
    }

    private static boolean supportsPositionFetch(VulkanPhysicalDevice physicalDevice) {
        return physicalDevice.hasDeviceExtension(VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME)
                && supportsFeature(physicalDevice, positionFetchFeature());
    }

    private static boolean supportsRayQuery(VulkanPhysicalDevice physicalDevice) {
        return physicalDevice.hasDeviceExtension(VK_KHR_RAY_QUERY_EXTENSION_NAME)
                && supportsFeature(physicalDevice, rayQueryFeature());
    }

    private static boolean supportsOmm(VulkanPhysicalDevice physicalDevice) {
        return ommRequested()
                && physicalDevice.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME)
                && supportsFeature(physicalDevice, ommFeature());
    }

    private static boolean supportsVrs(VulkanPhysicalDevice physicalDevice) {
        return physicalDevice.hasDeviceExtension(VK_KHR_FRAGMENT_SHADING_RATE_EXTENSION_NAME)
                && supportsFeature(physicalDevice, pipelineVrsFeature())
                && supportsFeature(physicalDevice, attachmentVrsFeature());
    }

    /** Optional extensions whose actual feature bits are usable — added but never required. */
    private static List<String> supportedOptionalExtensions(VulkanPhysicalDevice physicalDevice) {
        List<String> supported = new ArrayList<>();
        if (supportsPositionFetch(physicalDevice)) {
            supported.add(VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME);
        }
        if (supportsRayQuery(physicalDevice)) {
            supported.add(VK_KHR_RAY_QUERY_EXTENSION_NAME);
        }
        if (supportsOmm(physicalDevice)) {
            supported.add(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        }
        if (supportsVrs(physicalDevice)) {
            supported.add(VK_KHR_FRAGMENT_SHADING_RATE_EXTENSION_NAME);
        }
        return supported;
    }

    private static boolean ommRequested() {
        return CausticaConfig.Rt.Omm.ENABLED.value();
    }

    /** Query the raw {@code VkPhysicalDeviceFeatures} for {@code wideLines} support — no wrapper on
     * {@code VulkanPhysicalDevice} exposes this, so it is fetched directly off the raw handle. */
    private static boolean supportsWideLines(VulkanPhysicalDevice physicalDevice) {
        return supportsFeature(physicalDevice,
                vk10Feature("wideLines", VkPhysicalDeviceFeatures.WIDELINES));
    }

    private static SerBackend selectSerBackend(VulkanPhysicalDevice physicalDevice) {
        if (physicalDevice.hasDeviceExtension(VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME)
                && supportsFeature(physicalDevice, serFeature())) {
            return SerBackend.EXT;
        }
        return SerBackend.NONE;
    }

    private static List<VulkanFeature> mandatoryFeatures() {
        return List.of(
                vk10Feature("shaderStorageImageExtendedFormats",
                        VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEEXTENDEDFORMATS),
                vk10Feature("shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64),
                vk12Feature("bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS),
                vk12Feature("runtimeDescriptorArray", VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY),
                vk12Feature("shaderSampledImageArrayNonUniformIndexing",
                        VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING),
                accelerationStructureFeature(),
                rayTracingPipelineFeature());
    }

    private static String firstUnsupported(VulkanPhysicalDevice physicalDevice) {
        int apiVersion = physicalDevice.vkPhysicalDeviceProperties().apiVersion();
        if (apiVersion < VK_API_VERSION_1_2) {
            return "Vulkan 1.2 (got 0x" + Integer.toHexString(apiVersion)
                    + ", need 0x" + Integer.toHexString(VK_API_VERSION_1_2) + ")";
        }
        for (String ext : RT_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(ext)) {
                return ext;
            }
        }
        for (VulkanFeature feature : mandatoryFeatures()) {
            if (!supportsFeature(physicalDevice, feature)) {
                return "feature " + feature.name();
            }
        }
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
        rtRequested = false;
        serBackend = SerBackend.NONE;
        positionFetchEnabled = false;
        rayQueryEnabled = false;
        ommEnabled = false;
        vrsEnabled = false;
        String missing = firstUnsupported(physicalDevice);
        if (missing != null) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                CausticaMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks {}",
                        physicalDevice.deviceName(), missing);
            }
            return;
        }

        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        features.addAll(mandatoryFeatures());

        SerBackend selectedSerBackend = selectSerBackend(physicalDevice);
        if (selectedSerBackend == SerBackend.EXT) {
            features.add(serFeature());
        }

        // RADV (Mesa 26.1 + NAVI33) hard-recovers with a fixed SQC GPUVM READ_INVALID when closest-hit
        // uses gl_HitTriangleVertexPositionsEXT / ALLOW_DATA_ACCESS on freshly-built BLASes. Keep the
        // portable no-posfetch shader on radv until that path is proven stable; other vendors keep
        // position fetch when supported. Also force single-geometry terrain BLASes on radv.
        String driverInfo = String.valueOf(physicalDevice.driverInfo()).toLowerCase(java.util.Locale.ROOT);
        String deviceName = String.valueOf(physicalDevice.deviceName()).toLowerCase(java.util.Locale.ROOT);
        radvDriver = driverInfo.contains("radv") || deviceName.contains("radv");
        positionFetchEnabled = !radvDriver && supportsPositionFetch(physicalDevice);
        if (positionFetchEnabled) {
            features.add(positionFetchFeature());
        }
        rayQueryEnabled = supportsRayQuery(physicalDevice);
        if (rayQueryEnabled) {
            features.add(rayQueryFeature());
        }

        // Optional: wideLines (core VK10 feature, no extension). Its absence leaves the mandated 1px path.
        wideLinesEnabled = supportsWideLines(physicalDevice);
        if (wideLinesEnabled) {
            features.add(vk10Feature("wideLines", VkPhysicalDeviceFeatures.WIDELINES));
            maxLineWidth = physicalDevice.vkPhysicalDeviceProperties().limits().lineWidthRange(1);
        } else {
            maxLineWidth = 1.0f;
        }

        int colorSampleCounts = physicalDevice.vkPhysicalDeviceProperties().limits().framebufferColorSampleCounts();
        if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_4_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_4_BIT;
        } else if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_2_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_2_BIT;
        } else {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT;
        }

        ommEnabled = supportsOmm(physicalDevice);
        if (ommEnabled) {
            features.add(ommFeature());
        }

        vrsEnabled = supportsVrs(physicalDevice);
        if (vrsEnabled) {
            features.add(pipelineVrsFeature());
            features.add(attachmentVrsFeature());
        }

        args.set(2, features);
        rtRequested = true;
        serBackend = selectedSerBackend;
        CausticaMod.LOGGER.info(
                "Ray tracing: Vulkan 1.2 baseline, extensions={}, SER={}, positionFetch={}, rayQuery={}, OMM={}, VRS={}, wideLines={}, overlayMsaa={}x [{}]",
                RT_EXTENSIONS, serBackend.label, positionFetchEnabled, rayQueryEnabled, ommEnabled, vrsEnabled,
                wideLinesEnabled, overlayMsaaSamples, physicalDevice.deviceName());
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


            // Async compute — probe for dedicated compute queue
            probeAsyncCompute(device);

        } catch (Throwable t) {
            // A probe must never break device creation.
            CausticaMod.LOGGER.error("RT probe threw; continuing without RT", t);
        }
    }

    /**
     * Probe for a dedicated compute queue (different family from graphics) for async compute.
     * Availability is detected from queue-family capabilities rather than vendor assumptions.
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
