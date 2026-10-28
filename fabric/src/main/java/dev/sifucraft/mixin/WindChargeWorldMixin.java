package dev.sifucraft.mixin;

import dev.sifucraft.combat.SceneExplosions;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WindCharge.class)
public abstract class WindChargeWorldMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void sifucraft$awaitBlastGeometry(CallbackInfo ci){
        var self=(WindCharge)(Object)this;
        if(!self.level().isClientSide()&&!SceneExplosions.canAdvanceWind(self))ci.cancel();
    }
}
