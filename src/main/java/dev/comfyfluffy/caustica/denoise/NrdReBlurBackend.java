package dev.comfyfluffy.caustica.denoise;

import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * NVIDIA NRD ReBLUR (diffuse-only) SPIR-V port. Algorithm:
 * {@code nrd_reblur_prepass.comp} → {@code nrd_reblur_temporal.comp} →
 * {@code nrd_reblur_blur.comp} (×2) → {@code nrd_reblur_post.comp}. Owns its
 * own REBLUR_PREV_* accumulators; the host never sees the ping-pong.
 *
 * <p>Source: NVIDIA {@code RTXNRD/Source/VK/ReBLUR_PrePass.hlsl}, {@code _TemporalAccumulation.hlsl},
 * {@code _Blur.hlsl}, {@code _PostBlur.hlsl}.
 *
 * <p>License: NVIDIA Source Code License (BSD-3-style; tracked in {@code THIRD_PARTY_NOTICES.md}).
 *
 * <p>Diffuse-only first cut: requires a diffuse/specular radiance split from
 * {@code shaders/world/world.rgen} before {@code REBLUR_DIFFUSE} can run on
 * this codebase. Until the split lands, {@link #dispatch} throws.
 */
public final class NrdReBlurBackend implements CausticaDenoiseBackend {

    private boolean ready;
    private int width;
    private int height;

    @Override
    public String name() {
        return "nrd";
    }

    @Override
    public void init(long vkDevice, long vkPhysicalDevice) {
        this.ready = true;
    }

    @Override
    public void ensureSized(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void dispatch(MemoryStack stack, VkCommandBuffer cmd,
                         RtImage inColor, RtImage inNormal, RtImage inDepth, RtImage inMotion,
                         float mvScaleX, float mvScaleY,
                         RtImage outColor) {
        if (!ready) {
            return;
        }
        throw new UnsupportedOperationException(
                "NRD ReBLUR backend: blocked on diffuse/specular radiance split in shaders/world/world.rgen");
    }

    @Override
    public void resetHistory() {
        // NRD ReBLUR backend currently throws on dispatch (blocked on diffuse/spec split in
        // world.rgen); once the split lands and the backend is fully wired, this method
        // should clear REBLUR_PREV_* accumulators the same way FfxDenoiseBackend does.
    }

    @Override
    public void destroy() {
        ready = false;
        width = 0;
        height = 0;
    }

    @Override
    public boolean isReady() {
        return ready;
    }
}
