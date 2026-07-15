package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.CausticaDebugOverlay;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.TextAlignment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the {@link CausticaDebugOverlay} multi-line text on top of the main render target when
 * {@code caustica.rt.debugOverlay=true} (also exposed in the RT section of the Video Settings screen).
 *
 * <p>Hooks at the TAIL of {@link Hud#extractRenderState(GuiGraphicsExtractor, DeltaTracker)}, which
 * runs every frame for the regular HUD pass (chat, hotbar, status icons). TAIL means MC's own HUD
 * elements have already been added to the same {@link ActiveTextCollector}, so our lines stack on
 * top of the existing text without colliding. Because {@code GuiRendererMixin} + {@code GameRendererMixin}
 * already route HUD geometry into {@code RtUiOverlay} for SDR/HDR compositing, the overlay shows
 * correctly in both modes without any extra plumbing.
 *
 * <p>Defensive: the body is wrapped in a try/catch so a failure in the overlay (e.g. an unexpected
 * missing shader or extractor state) can't crash the GUI render pass. When {@code caustica.rt.debugOverlay}
 * is false the method returns on the first line — zero per-frame cost.
 */
@Mixin(Hud.class)
public abstract class InGameHudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void caustica$renderDebugOverlay(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!CausticaConfig.Rt.DebugOverlay.ENABLED.value()) {
            return;
        }
        try {
            ActiveTextCollector text = guiGraphics.textRenderer();
            List<String> lines = CausticaDebugOverlay.build();
            int y = 4;
            for (String line : lines) {
                text.accept(TextAlignment.LEFT, 4, y, net.minecraft.network.chat.Component.literal(line));
                y += 10;
            }
        } catch (Throwable t) {
            // Never let an overlay bug crash the GUI render pass.
        }
    }
}
