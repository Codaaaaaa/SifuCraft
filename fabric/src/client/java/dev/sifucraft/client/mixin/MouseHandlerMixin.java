package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While attached to Sifu, grabbing only flips Minecraft's own flag: the real cursor belongs to the
 * Sifu window, so the hidden Minecraft window must never put SDL into relative mouse mode.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    private boolean mouseGrabbed;

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void sifucraft$grabWithoutSdl(CallbackInfo ci) {
        if (!SifuCraftClient.attachedToSifu()) return;
        mouseGrabbed = true;
        ci.cancel();
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
    private void sifucraft$releaseWithoutSdl(CallbackInfo ci) {
        if (!SifuCraftClient.attachedToSifu()) return;
        mouseGrabbed = false;
        ci.cancel();
    }
}
