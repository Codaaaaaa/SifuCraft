package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sifucraft.combat.MachineProjectiles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ThrownLingeringPotion.class)
public abstract class LingeringPotionSourceMixin {
    @WrapOperation(method="onHitAsPotion",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean sifucraft$cloudOrigin(ServerLevel level,Entity cloud,Operation<Boolean> original){
        MachineProjectiles.cloud((ThrownLingeringPotion)(Object)this,cloud);return original.call(level,cloud);
    }
}
