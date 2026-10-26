package dev.sifucraft.client.mixin;

import dev.sifucraft.client.WorldCracks;
import dev.sifucraft.client.WorldEffects;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderType.class)
public abstract class WorldCracksMixin {
    // ModelBakery caches the vanilla types during startup, before a Sifu scope exists.
    // Select at preparation time instead of changing their one-time factory.
    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
    private void sifucraft$transparentCracks(CallbackInfoReturnable<PreparedRenderType> cir) {
        if (!WorldEffects.active()) return;
        var self = (RenderType)(Object)this;
        int stage = ModelBakery.DESTROY_TYPES.indexOf(self);
        if (stage < 0) stage = ModelBakery.DESTROY_TYPES_OIT.indexOf(self);
        if (stage >= 0) cir.setReturnValue(WorldCracks.renderType(stage).prepare());
    }
}
