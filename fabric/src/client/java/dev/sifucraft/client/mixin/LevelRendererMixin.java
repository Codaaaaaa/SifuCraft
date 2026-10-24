package dev.sifucraft.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep vanilla hands and GUI, replacing the mirrored world with transparent pixels. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void sifucraft$transparentWorld(CallbackInfo ci) {
        if (!SifuCraftClient.overlayActive()) return;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 0);
        ci.cancel();
    }
}
