package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.framegen.FrameGenSelector;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.material.RtMaterialSystem;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtWorkerPool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.SectionPos;

public final class CausticaClient implements ClientModInitializer {
	private static boolean rtInitDone = false;

	@Override
	public void onInitializeClient() {
		CausticaMod.LOGGER.info("Caustica client initialized");

		// The GpuDevice exists well before the first tick, so a one-shot at tick start
		// runs on the render thread with the device idle between frames.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (!VanillaRenderController.rtRuntimeWorkRequested()) {
				if (rtInitDone) {
					shutdownRt();
				}
				return;
			}

			// Bring up the RT device/context once; terrain residency + the composite follow below.
			if (!rtInitDone && RtDeviceBringup.rtRequested()) {
				RtContext ctx = RtContext.get();
				if (ctx != null) {
					rtInitDone = true;
					// v0.6: FFX disabled at source level. The probe below used to tryLoad the
					// official FFX Denoiser native + initialise the SPIR-V shadow/reflection
					// filters; both are bypassed now. (Kept commented for future re-enable.)
					// dev.comfyfluffy.caustica.ffx.denoiser.FfxDenoiserRuntime.INSTANCE.tryLoad();
				}
			}

			// P2: once RT is up, keep section residency synced to vanilla's loaded chunks around
			// the player — builds newly-in-range sections, frees out-of-range ones, per tick.
			if (rtInitDone) {
				RtContext ctx = RtContext.currentOrNull();
				if (ctx != null) {
					RtFrameStats.FRAME.beginIfInactive();
					// Bring the world pipeline + LabPBR atlases up before terrain tessellates, so per-prim
					// material flags resolve from the first section (PBR on join, no re-extract). No-op
					// until we're in a world with the block atlas loaded, or once already created.
					RtComposite.INSTANCE.ensureResourcesReady(ctx);
					RtTerrain.update(ctx);
					// Capability probing is provider-owned and idempotent.
					FrameGenSelector.probeAvailabilityOnce();
				}
			}
		});

		// Vanilla's full render-state invalidation (LevelExtractor.allChanged(): dimension change via
		// setLevel, render-distance change, F3+A) — drop RT terrain residency so it rebuilds for the new
		// world. Fixes stale geometry persisting across an End→Overworld switch (coords alone aren't
		// world-unique). Resource reloads do NOT fire this; that path is handled separately.
		// Block break hook: when the player breaks a block, mark the affected section
		// dirty (terrain re-extracts on the next tick) AND clear temporal history
		// (TAAU/NRD's accumulator). Without this, RT keeps rendering the pre-edit block
		// geometry for one full frame and TAAU/NRD ghosts the old colour over the new
		// state -- visible as "I broke the block but it came back".
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			long section = SectionPos.asLong(
					SectionPos.blockToSectionCoord(pos.getX()),
					SectionPos.blockToSectionCoord(pos.getY()),
					SectionPos.blockToSectionCoord(pos.getZ()));
			RtTerrain.markSectionDirty(section);
			RtComposite.INSTANCE.invalidateHistory();
		});

		InvalidateRenderStateCallback.EVENT.register(() -> {
			RtTerrain.requestFullClear();
			RtComposite.INSTANCE.resetFailureLatch(); // F3+A doubles as manual RT recovery after a latched failure
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			shutdownRt();
		});
	}

	private static void shutdownRt() {
		WorldRenderScaler.INSTANCE.destroy();
		RtWorkerPool.INSTANCE.shutdown(); // no-op if never started; stops worker threads on teardown
		RtUiOverlay.destroy(); // GUI redirect is not gated by rtInitDone; always release its TextureTarget
		if (!rtInitDone) {
			return;
		}

		RtContext ctx = RtContext.currentOrNull();
		if (ctx != null) {
			ctx.waitIdle();
			RtTerrain.shutdown(ctx);
			RtEntities.INSTANCE.shutdown();
		}
		RtComposite.INSTANCE.destroy();
		RtMaterialSystem.INSTANCE.destroy();
		FrameGenSelector.shutdown();
		if (ctx != null) {
			dev.comfyfluffy.caustica.rt.RtFramePresenter.INSTANCE.destroy(ctx.device());
		}
		if (ctx != null) {
			ctx.destroy();
		}
		rtInitDone = false;
	}
}
