package dev.sifucraft.client.mixin;

import dev.sifucraft.client.WorldEffects;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class WorldCloudMixin {
    @Inject(method = {"render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", "renderOit"},
            at = @At("HEAD"), cancellable = true)
    private void sifucraft$omitClouds(CallbackInfo ci) { if (WorldEffects.active()) ci.cancel(); }
}
