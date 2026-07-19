package dev.comfyfluffy.caustica.fsr;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.upscale.Upscaler;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector.Mode;
import dev.comfyfluffy.caustica.vendor.GpuVendor;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Classic AMD FSR 2.2 Vulkan upscaler ({@code libffx_fsr2_caustica.so}).
 *
 * <p>The native bridge declares its color and output resources as
 * {@code R16G16B16A16_SFLOAT}, while Caustica's bandwidth-oriented beauty plates are
 * {@code B10G11R11_UFLOAT}. These formats are not view-compatible, so compute passes
 * convert to and from RGBA16F around the SDK dispatch.
 */
public final class Fsr2ClassicUpscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final String LIB = "libffx_fsr2_caustica.so";
    private static final String PACK_SPV = "/caustica/rt/fsr_color_pack.comp.spv";
    private static final String UNPACK_SPV = "/caustica/rt/fsr_color_unpack.comp.spv";
    private static final String GUARD_SPV = "/caustica/rt/fsr_blackout_guard.comp.spv";

    private static final int Q_QUALITY = 1;
    private static final int Q_BALANCED = 2;
    private static final int Q_PERF = 3;
    private static final int Q_ULTRA = 4;

    // Full classic FSR2 flags for path-traced HDR + reverse-Z infinite depth.
    // bit0 HDR | bit3 DEPTH_INVERTED | bit4 DEPTH_INFINITE
    // Do NOT enable AUTO_EXPOSURE (bit5): we never bind an exposure texture; on RADV
    // that combination returns FFX_OK with a pure-black output plate.
    // Do NOT enable DISPLAY_RESOLUTION_MOTION_VECTORS: gMotion is render-res pixels.
    private static final int FLAGS_DEFAULT = (1 << 0) | (1 << 3) | (1 << 4);

    private final Fsr2ClassicLibrary lib;
    private final VulkanDevice device;
    private MemorySegment ctx = MemorySegment.NULL;
    private int featureRenderW = -1, featureRenderH = -1;
    private int featureDisplayW = -1, featureDisplayH = -1;
    private boolean ready;
    private boolean failed;
    private long frameIndex;
    private boolean hardReset = true;
    /**
     * After a pure-black FSR output (rc=0 but no energy), stay on blit fail-open until
     * a hard reset / recreate proves the path healthy again. Prevents a permanent black screen.
     */
    private boolean blackoutFailOpen;
    private int consecutiveBlackouts;
    private boolean blackoutLogged;

    private long lastFrameNanos = -1;
    private float lastDeltaTimeMs = 16.6f;

    /** RGBA16F staging at render res (FSR color input, alpha=1). */
    private RtImage colorRgba16;
    /** RGBA16F staging at display res (FSR output). */
    private RtImage outRgba16;
    // Shared DSL/pool/set/layout; separate pack/unpack/guard pipelines.
    private long convDsl, convPool, convLayout;
    private long[] convSets = new long[0];
    private long[][] convBoundViews = new long[0][];
    private long packPipe, unpackPipe;
    private long guardDsl, guardPool, guardSet, guardLayout, guardPipe;
    private long[] guardBoundViews;
    private boolean convReady;

    private Fsr2ClassicUpscaler(Fsr2ClassicLibrary lib, VulkanDevice device) {
        this.lib = lib;
        this.device = device;
    }

    public static Fsr2ClassicUpscaler tryCreate(GpuVendor gpu) {
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        try {
            Path so = resolveLibrary();
            if (so == null) {
                LOGGER.info("Classic FSR2 native {} not found; skipping FSR2 path", LIB);
                return null;
            }
            Fsr2ClassicLibrary lib = Fsr2ClassicLibrary.load(so);
            int ver = lib.probe();
            LOGGER.info("Classic FSR2 native loaded (probe={}) from {} on {}", ver, so, gpu.deviceName);
            return new Fsr2ClassicUpscaler(lib, device);
        } catch (Throwable t) {
            LOGGER.warn("Classic FSR2 init failed", t);
            return null;
        }
    }

    private static Path resolveLibrary() throws IOException {
        String override = System.getProperty("caustica.fsr2.path");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        Path dir = FabricLoader.getInstance().getGameDir()
                .resolve("caustica-fsr").resolve("natives").resolve("linux-x64");
        Files.createDirectories(dir);
        Path target = dir.resolve(LIB);
        try (InputStream in = Fsr2ClassicUpscaler.class.getResourceAsStream("/caustica/natives/linux-x64/" + LIB)) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                // Always overwrite: size-only checks leave stale SO with wrong MV/format
                // claims that black the FSR path on RADV while still returning rc=0.
                boolean rewrite = !Files.isRegularFile(target) || Files.size(target) != bytes.length;
                if (!rewrite && Files.isRegularFile(target)) {
                    byte[] existing = Files.readAllBytes(target);
                    rewrite = existing.length != bytes.length || !java.util.Arrays.equals(existing, bytes);
                }
                if (rewrite) {
                    Files.write(target, bytes);
                    target.toFile().setExecutable(true);
                    LOGGER.info("Extracted FSR2 native to {} ({} bytes)", target, bytes.length);
                }
                return target;
            }
        }
        if (Files.isRegularFile(target) && Files.size(target) > 50_000) {
            return target;
        }
        Path dev = Path.of("src/main/resources/caustica/natives/linux-x64").resolve(LIB);
        return Files.isRegularFile(dev) ? dev.toAbsolutePath() : null;
    }

    @Override
    public Mode mode() {
        return Mode.FSR_3;
    }

    @Override
    public boolean isReady() {
        return ready && !failed && !ctx.equals(MemorySegment.NULL);
    }

    @Override
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        int cfgQ = CausticaConfig.Rt.Upscaler.QUALITY.value();
        if (cfgQ <= 0) {
            return new int[]{displayWidth, displayHeight};
        }
        int q = mapQuality(cfgQ);
        float ratio = lib.upscaleRatio(q);
        if (ratio <= 0) {
            ratio = 1.5f;
        }
        int w = Math.max(1, Math.round(displayWidth / ratio));
        int h = Math.max(1, Math.round(displayHeight / ratio));
        return new int[]{w, h};
    }

    private static int mapQuality(int causticaQuality) {
        return switch (causticaQuality) {
            case 0 -> Q_QUALITY;
            case 1 -> Q_QUALITY;
            case 2 -> Q_BALANCED;
            case 3 -> Q_PERF;
            case 4 -> Q_ULTRA;
            default -> Q_QUALITY;
        };
    }

    @Override
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                 int quality, int featureFlags) {
        if (failed) {
            return false;
        }
        if (!ensureConvertPipelines()) {
            failed = true;
            return false;
        }
        if (ready && renderWidth == featureRenderW && renderHeight == featureRenderH
                && displayWidth == featureDisplayW && displayHeight == featureDisplayH) {
            ensureStaging(renderWidth, renderHeight, displayWidth, displayHeight);
            return colorRgba16 != null && outRgba16 != null;
        }
        try {
            if (!ctx.equals(MemorySegment.NULL)) {
                lib.destroy(ctx);
                ctx = MemorySegment.NULL;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
                long dev = device.vkDevice().address();
                long phys = device.vkDevice().getPhysicalDevice().address();
                int rc = lib.create(dev, phys, FLAGS_DEFAULT,
                        renderWidth, renderHeight, displayWidth, displayHeight, out);
                if (rc != 0) {
                    throw new IllegalStateException("caustica_ffx_fsr2_create failed: " + rc);
                }
                ctx = out.get(ValueLayout.ADDRESS, 0);
                if (ctx.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("null FSR2 context");
                }
            }
            featureRenderW = renderWidth;
            featureRenderH = renderHeight;
            featureDisplayW = displayWidth;
            featureDisplayH = displayHeight;
            ready = true;
            hardReset = true;
            frameIndex = 0;
            // New context: allow native FSR2 again (clear any prior session quarantine).
            blackoutFailOpen = false;
            blackoutLogged = false;
            consecutiveBlackouts = 0;
            ensureStaging(renderWidth, renderHeight, displayWidth, displayHeight);
            LOGGER.info(
                    "FSR2 classic context: {}x{} → {}x{} (full native path, RGBA16F α=1 pack + blackout guard)",
                    renderWidth, renderHeight, displayWidth, displayHeight);
            return colorRgba16 != null && outRgba16 != null;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("FSR2 classic create failed; disabling", t);
            return false;
        }
    }

    private void ensureStaging(int renderW, int renderH, int displayW, int displayH) {
        RtContext rt = RtContext.get();
        if (rt == null) {
            return;
        }
        if (colorRgba16 == null || colorRgba16.width != renderW || colorRgba16.height != renderH) {
            if (colorRgba16 != null) {
                colorRgba16.destroy();
            }
            colorRgba16 = rt.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "fsr2 color rgba16 " + renderW + "x" + renderH);
        }
        if (outRgba16 == null || outRgba16.width != displayW || outRgba16.height != displayH) {
            if (outRgba16 != null) {
                outRgba16.destroy();
            }
            outRgba16 = rt.createStorageImage(displayW, displayH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "fsr2 out rgba16 " + displayW + "x" + displayH);
        }
    }

    @Override
    public boolean evaluate(long cmdAddr, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                            RtImage specularMotion, RtImage specularHitDistance, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!isReady() || color == null || depth == null || motion == null || out == null) {
            return false;
        }
        // Sticky quarantine: after repeated native blackouts, skip native and let composite blit.
        if (blackoutFailOpen) {
            if (!blackoutLogged) {
                blackoutLogged = true;
                LOGGER.error(
                        "FSR2 native path quarantined this session after blackout(s). "
                                + "Using 1:1 blit upscale; denoise still runs. "
                                + "Restart game or toggle upscaler to retry native FSR2.");
            }
            return false;
        }
        if (!convReady || colorRgba16 == null || outRgba16 == null) {
            ensureStaging(renderWidth, renderHeight, displayWidth, displayHeight);
            if (!ensureConvertPipelines() || colorRgba16 == null || outRgba16 == null) {
                return false;
            }
        }
        try {
            long currentNanos = System.nanoTime();
            if (lastFrameNanos > 0) {
                float deltaMs = (currentNanos - lastFrameNanos) / 1_000_000.0f;
                deltaMs = Math.max(1.0f, Math.min(100.0f, deltaMs));
                lastDeltaTimeMs = deltaMs;
            }
            lastFrameNanos = currentNanos;

            float jx = jitterX;
            float jy = jitterY;

            float fovY = (float) Math.toRadians(70.0);
            float cameraNear = 0.05f;
            if (viewToClip != null) {
                float m11 = viewToClip.m11();
                if (Math.abs(m11) > 1e-5f) {
                    fovY = 2.0f * (float) Math.atan(1.0f / Math.abs(m11));
                }
                float m22 = viewToClip.m22();
                float m32 = viewToClip.m32();
                if (Math.abs(m22) < 0.001f && m32 < 0) {
                    cameraNear = -m32;
                }
            }

            float sharp = CausticaConfig.Rt.Upscaler.SHARPEN.value()
                    ? Math.max(0f, Math.min(1f, CausticaConfig.Rt.Upscaler.SHARPNESS.value()))
                    : -1f;
            // preExposure must stay ~1.0 for HDR path-traced plates. Sharpness is packed as 2+s.
            float preExpPacked = sharp < 0f ? 1.0f : (2.0f + sharp);

            VkCommandBuffer cmd = new VkCommandBuffer(cmdAddr, device.vkDevice());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                barrier(stack, cmd, color.image,
                        VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);

                // B10G11R11 beauty → the RGBA16F format declared by the native bridge.
                dispatchConvert(stack, cmd, 0, packPipe, color, colorRgba16, renderWidth, renderHeight);
                barrier(stack, cmd, colorRgba16.image,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);

                // Always clear FSR output staging before native dispatch so a partial write
                // cannot leave uninitialized black/NaN texels that stick in history.
                clearImageBlack(stack, cmd, outRgba16);

                int rc = lib.dispatch(ctx, cmdAddr,
                        colorRgba16.image, colorRgba16.view,
                        depth.image, depth.view,
                        motion.image, motion.view,
                        outRgba16.image, outRgba16.view,
                        renderWidth, renderHeight,
                        jx, jy, lastDeltaTimeMs, preExpPacked,
                        // FSR2's reverse-infinite transform still derives its scale from
                        // min/max(cameraNear, cameraFar). Passing far=0 makes that scale 0
                        // after the inverted-depth swap. A finite positive sentinel keeps
                        // the infinite-depth permutation well-defined (the flag, not this
                        // magnitude, selects the infinite projection formula).
                        cameraNear, 1_000_000.0f, fovY,
                        hardReset ? 1 : 0);

                if (frameIndex < 5 || frameIndex % 300 == 0) {
                    LOGGER.info(
                            "FSR2 dispatch #{} rc={} jitter=({}, {}) fovY={}° near={} dt={}ms sharp={} render={}x{} → {}x{} reset={}",
                            frameIndex, rc,
                            String.format(java.util.Locale.ROOT, "%.3f", jx),
                            String.format(java.util.Locale.ROOT, "%.3f", jy),
                            String.format(java.util.Locale.ROOT, "%.1f", Math.toDegrees(fovY)),
                            String.format(java.util.Locale.ROOT, "%.3f", cameraNear),
                            String.format(java.util.Locale.ROOT, "%.2f", lastDeltaTimeMs),
                            String.format(java.util.Locale.ROOT, "%.2f", sharp),
                            renderWidth, renderHeight, displayWidth, displayHeight, hardReset);
                }
                hardReset = false;
                frameIndex++;
                if (rc != 0) {
                    consecutiveBlackouts++;
                    if (consecutiveBlackouts >= 3) {
                        blackoutFailOpen = true;
                    }
                    throw new IllegalStateException("caustica_ffx_fsr2_dispatch failed: " + rc);
                }

                barrier(stack, cmd, outRgba16.image,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
                // Guarded unpack: pure-black/NaN FSR out → nearest upsample of packed input.
                if (guardPipe != 0L) {
                    dispatchGuard(stack, cmd, outRgba16, colorRgba16, out,
                            displayWidth, displayHeight, renderWidth, renderHeight);
                } else {
                    dispatchConvert(stack, cmd, 1, unpackPipe, outRgba16, out, displayWidth, displayHeight);
                }
                barrier(stack, cmd, out.image,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            }
            consecutiveBlackouts = 0;
            return true;
        } catch (Throwable t) {
            LOGGER.error("FSR2 classic evaluate failed — composite will blit-fallback", t);
            consecutiveBlackouts++;
            if (consecutiveBlackouts >= 3) {
                blackoutFailOpen = true;
            }
            return false;
        }
    }

    private void clearImageBlack(MemoryStack stack, VkCommandBuffer cmd, RtImage image) {
        if (image == null) {
            return;
        }
        org.lwjgl.vulkan.VkClearColorValue black = org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
        for (int i = 0; i < 4; i++) {
            black.float32(i, 0.0f);
        }
        org.lwjgl.vulkan.VkImageSubresourceRange.Buffer range =
                org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearColorImage(cmd, image.image, VK10.VK_IMAGE_LAYOUT_GENERAL, black, range);
        barrier(stack, cmd, image.image,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }

    /**
     * Sticky latch: true while native FSR is quarantined so composite prefers blit.
     */
    public boolean consumeBlackoutFailOpen() {
        return blackoutFailOpen;
    }

    private boolean ensureConvertPipelines() {
        if (convReady && packPipe != 0L && unpackPipe != 0L) {
            return true;
        }
        RtContext rt = RtContext.get();
        if (rt == null) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vk = rt.vk();
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(fsr-convert)");
            convDsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(2).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(fsr-convert)");
            convPool = p.get(0);

            convSets = new long[2];
            convBoundViews = new long[2][];
            for (int i = 0; i < convSets.length; i++) {
                VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(convPool).pSetLayouts(stack.longs(convDsl));
                LongBuffer pSet = stack.mallocLong(1);
                check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet),
                        "vkAllocateDescriptorSets(fsr-convert " + i + ")");
                convSets[i] = pSet.get(0);
            }

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(convDsl)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(fsr-convert)");
            convLayout = p.get(0);

            packPipe = createPipe(vk, stack, convLayout, PACK_SPV, "fsr-pack");
            unpackPipe = createPipe(vk, stack, convLayout, UNPACK_SPV, "fsr-unpack");
            // Best-effort blackout guard (3 storage images). Failure leaves guardPipe=0 → plain unpack.
            try {
                ensureGuardPipeline(vk, stack);
            } catch (Throwable gt) {
                LOGGER.warn("FSR2 blackout guard pipeline unavailable; plain unpack only", gt);
                destroyGuardPipeline(vk);
            }
            convReady = true;
            return true;
        } catch (Throwable t) {
            LOGGER.error("FSR2 color pack/unpack pipeline failed", t);
            destroyConvertPipelines();
            return false;
        }
    }

    private void ensureGuardPipeline(VkDevice vk, MemoryStack stack) {
        if (guardPipe != 0L) {
            return;
        }
        VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(3, stack);
        for (int i = 0; i < 3; i++) {
            binds.get(i).binding(i).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorSetLayout(vk,
                VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds), null, p),
                "vkCreateDescriptorSetLayout(fsr-guard)");
        guardDsl = p.get(0);

        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
        poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(3);
        check(VK10.vkCreateDescriptorPool(vk,
                VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes), null, p),
                "vkCreateDescriptorPool(fsr-guard)");
        guardPool = p.get(0);

        LongBuffer pSet = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk,
                VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(guardPool).pSetLayouts(stack.longs(guardDsl)), pSet),
                "vkAllocateDescriptorSets(fsr-guard)");
        guardSet = pSet.get(0);

        VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
        check(VK10.vkCreatePipelineLayout(vk,
                VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(guardDsl)).pPushConstantRanges(pcr), null, p),
                "vkCreatePipelineLayout(fsr-guard)");
        guardLayout = p.get(0);
        guardPipe = createPipe(vk, stack, guardLayout, GUARD_SPV, "fsr-guard");
        LOGGER.info("FSR2 blackout guard pipeline ready (fail-open nearest upsample on pure-black FSR out)");
    }

    private void destroyGuardPipeline(VkDevice vk) {
        if (guardPipe != 0L) {
            VK10.vkDestroyPipeline(vk, guardPipe, null);
            guardPipe = 0L;
        }
        if (guardLayout != 0L) {
            VK10.vkDestroyPipelineLayout(vk, guardLayout, null);
            guardLayout = 0L;
        }
        if (guardPool != 0L) {
            VK10.vkDestroyDescriptorPool(vk, guardPool, null);
            guardPool = 0L;
        }
        if (guardDsl != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, guardDsl, null);
            guardDsl = 0L;
        }
        guardSet = 0L;
        guardBoundViews = null;
    }

    private void dispatchGuard(MemoryStack stack, VkCommandBuffer cmd,
                               RtImage fsrRgba, RtImage inRgba, RtImage outB10,
                               int displayW, int displayH, int renderW, int renderH) {
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
        RtImage[] imgs = {fsrRgba, inRgba, outB10};
        for (int i = 0; i < 3; i++) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).imageView(imgs[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(guardSet).dstBinding(i).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
        }
        long[] views = {fsrRgba.view, inRgba.view, outB10.view};
        if (!java.util.Arrays.equals(guardBoundViews, views)) {
            VK10.vkUpdateDescriptorSets(device.vkDevice(), writes, null);
            guardBoundViews = views;
        }

        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, guardPipe);
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, guardLayout, 0,
                stack.longs(guardSet), null);
        ByteBuffer push = stack.malloc(16);
        push.putInt(0, displayW);
        push.putInt(4, displayH);
        push.putInt(8, renderW);
        push.putInt(12, renderH);
        VK10.vkCmdPushConstants(cmd, guardLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (displayW + 7) / 8, (displayH + 7) / 8, 1);
    }

    private static long createPipe(VkDevice vk, MemoryStack stack, long layout, String spv, String tag) {
        long module = loadModule(vk, stack, spv);
        try {
            LongBuffer p = stack.mallocLong(1);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, p),
                    "vkCreateComputePipelines(" + tag + ")");
            return p.get(0);
        } finally {
            VK10.vkDestroyShaderModule(vk, module, null);
        }
    }

    private void dispatchConvert(MemoryStack stack, VkCommandBuffer cmd, int setIndex, long pipe,
                                 RtImage in, RtImage out, int width, int height) {
        long set = convSets[setIndex];
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        VkDescriptorImageInfo.Buffer inInfo = VkDescriptorImageInfo.calloc(1, stack);
        inInfo.get(0).imageView(in.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(inInfo);
        VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack);
        outInfo.get(0).imageView(out.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outInfo);
        long[] views = {in.view, out.view};
        if (!java.util.Arrays.equals(convBoundViews[setIndex], views)) {
            VK10.vkUpdateDescriptorSets(device.vkDevice(), writes, null);
            convBoundViews[setIndex] = views;
        }

        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, convLayout, 0,
                stack.longs(set), null);
        ByteBuffer push = stack.malloc(16);
        push.putInt(0, width);
        push.putInt(4, height);
        push.putInt(8, 0);
        push.putInt(12, 0);
        VK10.vkCmdPushConstants(cmd, convLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
    }

    private static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image,
                                int srcStage, int srcAccess, int dstStage, int dstAccess) {
        VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(1, stack);
        barriers.get(0).sType$Default()
                .srcStageMask(srcStage)
                .srcAccessMask(srcAccess)
                .dstStageMask(dstStage)
                .dstAccessMask(dstAccess)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .image(image)
                .subresourceRange(it -> it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String path) {
        byte[] bytes;
        try (InputStream in = Fsr2ClassicUpscaler.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + path);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + path, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + path + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private void destroyConvertPipelines() {
        VkDevice vk = device.vkDevice();
        destroyGuardPipeline(vk);
        if (packPipe != 0L) {
            VK10.vkDestroyPipeline(vk, packPipe, null);
            packPipe = 0L;
        }
        if (unpackPipe != 0L) {
            VK10.vkDestroyPipeline(vk, unpackPipe, null);
            unpackPipe = 0L;
        }
        if (convLayout != 0L) {
            VK10.vkDestroyPipelineLayout(vk, convLayout, null);
            convLayout = 0L;
        }
        if (convPool != 0L) {
            VK10.vkDestroyDescriptorPool(vk, convPool, null);
            convPool = 0L;
        }
        if (convDsl != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, convDsl, null);
            convDsl = 0L;
        }
        convSets = new long[0];
        convBoundViews = new long[0][];
        convReady = false;
    }

    @Override
    public void requestResetHistory() {
        hardReset = true;
        blackoutFailOpen = false;
        consecutiveBlackouts = 0;
    }

    @Override
    public void destroy() {
        if (!ctx.equals(MemorySegment.NULL)) {
            try {
                lib.destroy(ctx);
            } catch (Throwable ignored) {
            }
            ctx = MemorySegment.NULL;
        }
        if (colorRgba16 != null) {
            colorRgba16.destroy();
            colorRgba16 = null;
        }
        if (outRgba16 != null) {
            outRgba16.destroy();
            outRgba16 = null;
        }
        destroyConvertPipelines();
        ready = false;
    }
}
