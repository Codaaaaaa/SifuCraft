package dev.sifucraft.mixin;

import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.combat.FluidMotion;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Create: a weighted ejector's server-side launch of a Sifu NPC proxy
 * becomes a real Native flight instead of a mirror velocity the snapshot discards. */
@Pseudo
@Mixin(targets = "com.zurrtum.create.content.logistics.depot.EntityLauncher")
public abstract class CreateEjectorMixin {
    @Inject(method = "applyMotion(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Direction;)V", at = @At("TAIL"), require = 0)
    private void sifucraft$nativeLaunch(Entity entity, Direction facing, CallbackInfo ci) {
        if (entity instanceof EnemyProxy proxy && !proxy.level().isClientSide()) FluidMotion.launched(proxy, entity.getDeltaMovement());
    }
}
