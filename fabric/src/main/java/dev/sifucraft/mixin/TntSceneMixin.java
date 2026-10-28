package dev.sifucraft.mixin;

import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain original fuse and motion while the owning map's collision is unavailable. */
@Mixin(PrimedTnt.class)
public abstract class TntSceneMixin {
    @Shadow private float explosionPower;
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sifucraft$waitForScene(CallbackInfo ci) {
        var tnt = (PrimedTnt)(Object)this;
        var needed=tnt.getBoundingBox().expandTowards(tnt.getDeltaMovement());
        if(tnt.getFuse()<=1)needed=needed.inflate(Math.max(0,explosionPower)*2);
        if (!SceneFluidGeometry.available(tnt.level()) || !SceneFluidGeometry.knownVolume(tnt.level(),needed)||!dev.sifucraft.combat.SceneExplosions.canAdvance(tnt) ||
                !SceneFluidGeometry.inActiveMap(tnt.level(), tnt.blockPosition())) ci.cancel();
    }
}
