package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
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
    @WrapMethod(method="tick")
    private void sifucraft$oneMotionObservation(Operation<Void> original){
        var view=WorldMotionWorld.current();
        if(view==null){original.call();return;}
        WorldMotionWorld.withView(view,()->{original.call();return null;});
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sifucraft$waitForScene(CallbackInfo ci) {
        var tnt = (PrimedTnt)(Object)this;
        var needed=tnt.getBoundingBox().expandTowards(tnt.getDeltaMovement());
        if (!SceneFluidGeometry.available(tnt.level()) || !SceneFluidGeometry.knownVolume(tnt.level(),needed)||
                tnt.getFuse()<=1&&!SceneFluidGeometry.blastVolumeReady(tnt.level(),needed.inflate(Math.max(0,explosionPower)*2))||!dev.sifucraft.combat.SceneExplosions.canAdvance(tnt) ||
                !SceneFluidGeometry.inActiveMap(tnt.level(), tnt.blockPosition())) ci.cancel();
    }
}
