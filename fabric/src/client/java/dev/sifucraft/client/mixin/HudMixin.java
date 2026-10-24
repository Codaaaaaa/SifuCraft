package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The vignette uses destination-colour blending, which needs the final game's scene. */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractCrosshair", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V", ordinal = 0))
    private void sifucraft$crosshairDrawn(CallbackInfo ci) {
        if (SifuCraftClient.overlayActive()) SifuCraftClient.markCrosshair();
    }

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void sifucraft$omitVignetteInCapture(CallbackInfo ci) {
        if (SifuCraftClient.overlayActive()) ci.cancel();
    }
}
