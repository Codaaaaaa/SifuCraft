package dev.sifucraft.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.sifucraft.client.SifuCraftClient;
import dev.sifucraft.client.WorldEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep vanilla hands and GUI, replacing the mirrored world with transparent pixels. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void sifucraft$transparentWorld(CallbackInfo ci) {
        if (WorldEffects.active()) {
            WorldEffects.prepare(levelRenderState);
            return;
        }
        if (!SifuCraftClient.overlayActive()) return;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 0);
        ci.cancel();
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private Vector4f sifucraft$transparentClear(Vector4f original) {
        return WorldEffects.active() ? new Vector4f(0, 0, 0, 0) : original;
    }

    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void sifucraft$omitSky(CallbackInfo ci) { if (WorldEffects.active()) ci.cancel(); }

    @Inject(method = "render", at = @At("RETURN"))
    private void sifucraft$worldBeforeHands(CallbackInfo ci) {
        if (WorldEffects.active()) SifuCraftClient.captureWorld(levelRenderState.cameraRenderState);
    }
}
