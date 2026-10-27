package dev.sifucraft.mixin;

import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain original fuse and motion while the owning map's collision is unavailable. */
@Mixin(PrimedTnt.class)
public abstract class TntSceneMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sifucraft$waitForScene(CallbackInfo ci) {
        var tnt = (PrimedTnt)(Object)this;
        if (!SceneFluidGeometry.available(tnt.level()) || !dev.sifucraft.combat.SceneExplosions.canAdvance(tnt) ||
                !SceneFluidGeometry.inActiveMap(tnt.level(), tnt.blockPosition())) ci.cancel();
    }
}
