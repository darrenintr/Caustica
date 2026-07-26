package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-lifecycle hooks that must run before Minecraft tears the renderer down. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void caustica$destroyUiOverlayBeforeRendererShutdown(CallbackInfo ci) {
        RtUiOverlay.destroy();
    }
}
