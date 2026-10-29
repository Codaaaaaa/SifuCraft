package dev.sifucraft.client.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.3 depth readback sets GL_NONE on its read FBO but the color path does not reset it. */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class WorldReadbackMixin {
    @Inject(method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_readPixels(IIIIIIJ)V"))
    private void sifucraft$colorReadBuffer(GpuTexture texture, GpuBuffer buffer, long offset, Runnable complete,
                                         int mip, int x, int y, int width, int height, CallbackInfo ci) {
        if (Boolean.getBoolean("sifucraft.worldLayers") && texture.getFormat().hasColorAspect())
            GlStateManager._glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
    }
}
