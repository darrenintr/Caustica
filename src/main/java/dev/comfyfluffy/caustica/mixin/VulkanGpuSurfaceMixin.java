package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.framegen.FrameGen;
import dev.comfyfluffy.caustica.framegen.FrameGenSelector;
import dev.comfyfluffy.caustica.rt.RtFramePresenter;
import dev.comfyfluffy.caustica.rt.RtHdr;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


/**
 * HDR Phase 0 capability logging + PQ swapchain selection.
 *
 * <p>The {@link VulkanGpuSurface} constructor holds both the live {@code VkSurfaceKHR} and the physical
 * device, so we enumerate the surface's formats/color spaces there (once) for diagnostics.
 *
 * <p>When {@code caustica.rt.hdr.pqSwapchain} is on and the surface advertises HDR10_ST2084 (ST.2084/PQ,
 * paired with whatever pixel format the surface offers for it — commonly a 10-bit UNORM, but this is
 * discovered by scanning the surface's advertised formats rather than assumed), we steer Minecraft's
 * swapchain to it: vanilla {@code pickSwapchainSurfaceFormat} only accepts SDR (color space 0, format 37/44)
 * and {@code configure} hardcodes {@code imageColorSpace(0)}. We override the picked format and the
 * color-space arg. Falls back to the vanilla SDR path when the flag is off or PQ is unavailable; default off.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	private static final int VK_COLOR_SPACE_HDR10_ST2084_EXT = 1000104008;

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private long surface;

	@Shadow
	@Final
	private org.lwjgl.vulkan.VkQueue presentQueue;

	@Shadow
	private long swapchain;

	@Shadow
	@Final
	private int swapchainImageFormat;

	@Shadow
	@Final
	private LongList swapchainImages;

	@Shadow
	private int currentImageIndex;

	@Shadow
	private int swapchainWidth;

	@Shadow
	private int swapchainHeight;

	@Shadow
	@Final
	private long[] acquireSemaphores;

	@Shadow
	private int currentAcquireSemaphore;

	@Shadow
	private long[] presentSemaphores;

	@Unique
	private int caustica$colorSpace = 0;

	@Inject(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;J)V", at = @At("TAIL"))
	private void caustica$logHdrCapabilities(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		try {
			RtHdr.logSurfaceCapabilities(this.device.vkDevice().getPhysicalDevice(), this.surface, this.swapchainImageFormat);
		} catch (Throwable t) {
			// Diagnostics only — never let HDR logging break surface creation.
		}
	}

	/**
	 * Pick a PQ (HDR10_ST2084) surface format when requested + available, before vanilla's SDR-only selection
	 * runs. Scans for any format the surface pairs with that color space rather than assuming a specific one
	 * (IHVs commonly pair it with a 10-bit UNORM like A2R10G10B10, but this must not be hardcoded). Sets
	 * {@link #caustica$colorSpace} so {@code configure} can pass the matching color space.
	 */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
	private void caustica$pickPqFormat(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		if (!CausticaConfig.Rt.Hdr.enabled()) {
			return;
		}
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
				this.caustica$colorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT;
				CausticaMod.LOGGER.info("HDR: selecting PQ swapchain (format={}, colorSpace=HDR10_ST2084)", f.format());
				cir.setReturnValue(f);
				return;
			}
		}
		CausticaMod.LOGGER.warn("HDR: PQ swapchain requested but HDR10_ST2084 was not advertised by the surface; "
				+ "using SDR (enable OS/display HDR; on Linux use a native Wayland session with HDR enabled in the compositor)");
	}

	/** Replace the hardcoded {@code imageColorSpace(0)} with the PQ color space when one was selected. */
	@ModifyArg(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
			index = 0)
	private int caustica$overrideColorSpace(int original) {
		return this.caustica$colorSpace != 0 ? this.caustica$colorSpace : original;
	}

	/** Log presentation constraints for the active frame-generation backend after swapchain reconfiguration. */
	@Inject(method = "configure", at = @At("TAIL"))
	private void caustica$logFrameGenerationPresentMode(GpuSurface.Configuration config, CallbackInfo ci) {
		// MAILBOX/IMMEDIATE may replace an earlier queued image before scanout. Providers that emit
		// multiple presents therefore require FIFO to guarantee each generated frame reaches the display.
		FrameGen provider = FrameGenSelector.current();
		if (provider.isEnabled() && provider.requiresFifoPresent()) {
			CausticaMod.LOGGER.info("{}: swapchain present mode = {} (FIFO required for generated frames; "
					+ "MAILBOX/IMMEDIATE may drop queued frames)", provider.name(), config.presentMode());
		}
	}

	/**
	 * Step C — world-only HDR present. When the RT renderer has a fresh PQ HDR image and the swapchain is
	 * PQ, blit that image straight into the swapchain instead of Minecraft's SDR main target. Replaces the
	 * vanilla blit entirely (the SDR target + its UI are bypassed for now; UI compositing is a later step).
	 *
	 * <p>Because this cancels {@code blitFromTexture} at HEAD, the normal {@code caustica$presentGeneratedFrames}
	 * TAIL inject below never runs on HDR frames — so the active frame-generation backend's extra-present step is invoked explicitly here,
	 * right after the real HDR frame is recorded, using the just-composited {@code hdrDisplayImage} (already
	 * UI-composited by {@code presentHdr}) as the interpolation source instead of the SDR main target.
	 */
	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void caustica$presentHdr(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0) {
			return;
		}
		RtComposite rt = RtComposite.INSTANCE;
		long swapchainImage = this.swapchainImages.getLong(this.currentImageIndex);
		long acquireSem = this.acquireSemaphores[this.currentAcquireSemaphore];
		long presentSem = this.presentSemaphores[this.currentImageIndex];
		if (rt.isHdrPresentActive()) {
			VulkanCommandEncoder enc = (VulkanCommandEncoder) commandEncoder;
			rt.presentHdr(enc, swapchainImage, this.swapchainWidth, this.swapchainHeight, acquireSem, presentSem);
			caustica$presentGeneratedFramesHdr(enc, rt);
			ci.cancel();
			return;
		}
		// Non-RT frame (menu, title panorama, loading screen) on a PQ swapchain: vanilla's raw SDR blit would
		// misdisplay (SDR bytes reinterpreted as PQ codes). Convert sRGB -> PQ at paper white instead. Falls
		// through to vanilla SDR if conversion resources aren't ready or the source view is not a Vulkan view.
		if (rt.isPqSdrPresentActive()) {
			long sdrView = caustica$vkImageView(textureView);
			if (sdrView != 0L && rt.presentSdrToPq((VulkanCommandEncoder) commandEncoder, swapchainImage,
					this.swapchainWidth, this.swapchainHeight, sdrView, acquireSem, presentSem)) {
				ci.cancel();
			}
		}
	}

	@Unique
	private static long caustica$vkImageView(GpuTextureView view) {
		return view instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView v ? v.vkImageView() : 0L;
	}

	/**
	 * Frame-generation presentation seam: after Minecraft blits the real frame into its acquired swapchain
	 * image (but before {@code present()} shows it), present generated frames into additional swapchain images
	 * via {@link RtFramePresenter}, so the display order is generated-then-real. The HDR/PQ path invokes the
	 * same seam explicitly because it cancels {@code blitFromTexture} before this TAIL injection.
	 */
	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void caustica$presentGeneratedFrames(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0 || !RtFramePresenter.INSTANCE.isActive()) {
			return;
		}
		long srcImage = textureView.texture() instanceof com.mojang.blaze3d.vulkan.VulkanGpuTexture t ? t.vkImage() : 0L;
		long srcView = caustica$vkImageView(textureView);
		if (srcImage == 0L) {
			return;
		}
		int generatedCount = FrameGenSelector.current().effectiveMultiFrameCount();
		RtFramePresenter.INSTANCE.prepareExtraFrames((VulkanCommandEncoder) commandEncoder, this.device,
				this.swapchain, this.swapchainImages, this.presentSemaphores,
				this.swapchainWidth, this.swapchainHeight,
				srcView, srcImage, textureView.getWidth(0), textureView.getHeight(0), generatedCount, false);
	}

	/**
	 * Frame generation on the HDR present path: same extra-present mechanism as {@link #caustica$presentGeneratedFrames},
	 * but sourced from the HDR backbuffer ({@link RtComposite#hdrBackbufferView()}/{@code hdrBackbufferImage()})
	 * since HDR frames never reach that TAIL inject (HEAD cancels {@code blitFromTexture} above). No-op if FG
	 * isn't active or the HDR backbuffer isn't available (shouldn't happen right after a successful
	 * {@code presentHdr} call, but mirrors the defensive {@code srcImage == 0L} check in the SDR path).
	 */
	@Unique
	private void caustica$presentGeneratedFramesHdr(VulkanCommandEncoder enc, RtComposite rt) {
		if (this.currentImageIndex < 0 || !RtFramePresenter.INSTANCE.isActive()) {
			return;
		}
		long hdrView = rt.hdrBackbufferView();
		long hdrImage = rt.hdrBackbufferImage();
		if (hdrImage == 0L) {
			return;
		}
		int generatedCount = FrameGenSelector.current().effectiveMultiFrameCount();
		RtFramePresenter.INSTANCE.prepareExtraFrames(enc, this.device, this.swapchain, this.swapchainImages,
				this.presentSemaphores, this.swapchainWidth, this.swapchainHeight,
				hdrView, hdrImage, this.swapchainWidth, this.swapchainHeight, generatedCount, true);
	}

	// Present the FG-generated frame(s) acquired/recorded at blitFromTexture TAIL — at present() HEAD, after
	// Minecraft.java's encoder.submit() has flushed (so our present semaphores are signaled) and before MC
	// presents the real frame, giving display order generated-then-real.
	@Inject(method = "present", at = @At("HEAD"))
	private void caustica$flushGeneratedPresents(CallbackInfo ci) {
		RtFramePresenter.INSTANCE.flushPendingPresents(this.swapchain, this.presentQueue);
	}
}
