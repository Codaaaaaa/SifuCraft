package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = {"bobHurt", "bobView"}, at = @At("HEAD"), cancellable = true)
    private void sifucraft$nativeCameraMotion(CallbackInfo ci) {
        if (SifuCraftClient.nativeWorldView() != null) ci.cancel();
    }
    @Inject(method = "extract", at = @At("HEAD"))
    private void sifucraft$resetGui(CallbackInfo ci) { SifuCraftClient.beginGuiFrame(); }

    @Inject(method = "render", at = @At("RETURN"))
    private void sifucraft$captureHud(CallbackInfo ci) {
        SifuCraftClient.captureOverlay();
    }
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0)
    private org.joml.Matrix4f sifucraft$actualWorldProjection(org.joml.Matrix4f matrix) {
        SifuCraftClient.worldProjection(matrix);
        return matrix;
    }
}
