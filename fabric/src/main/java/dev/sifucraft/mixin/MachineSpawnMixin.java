package dev.sifucraft.mixin;

import dev.sifucraft.combat.MachineProjectiles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom dispenser behaviors (notably rockets) do not use Projectile.spawnProjectile. */
@Mixin(ServerLevel.class)
public abstract class MachineSpawnMixin {
    @Inject(method="addFreshEntity",at=@At("RETURN"))
    private void sifucraft$machineProduct(Entity entity,CallbackInfoReturnable<Boolean> cir){
        if(cir.getReturnValueZ()&&entity instanceof Projectile projectile)MachineProjectiles.created(projectile);
    }
}
