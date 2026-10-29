package dev.sifucraft.mixin;

import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.combat.VanillaFluidBody;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional Create: an NPC's flight body overlaps its own hit proxy, so proxies never
 * block it on a belt. Native's sweep still stops it against other actual NPCs. */
@Pseudo
@Mixin(targets = "com.zurrtum.create.content.kinetics.belt.transport.BeltMovementHandler")
public abstract class CreateBeltMixin {
    @Inject(method = "shouldIgnoreBlocking(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void sifucraft$proxyNeverBlocksBody(Entity a, Entity b, CallbackInfoReturnable<Boolean> cir) {
        if (a instanceof VanillaFluidBody && b instanceof EnemyProxy || b instanceof VanillaFluidBody && a instanceof EnemyProxy) cir.setReturnValue(true);
    }
}
