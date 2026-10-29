package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sifucraft.combat.BoatPassengers;
import dev.sifucraft.combat.EnemyProxy;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A moving rideable minecart picks native NPCs up like vanilla mobs; the rails then carry them. */
@Mixin({OldMinecartBehavior.class,NewMinecartBehavior.class})
public abstract class MinecartPassengersMixin {
    @WrapOperation(method={"pushAndPickupEntities","pickupEntities"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> sifucraft$nearby(Level level,Entity cart,AABB box,Predicate<Entity> predicate,Operation<List<Entity>> original){
        return BoatPassengers.candidates(cart,box,original.call(level,cart,box,predicate));
    }
    @WrapOperation(method={"pushAndPickupEntities","pickupEntities"},at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;startRiding(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean sifucraft$reserve(Entity entity,Entity cart,Operation<Boolean> original){
        return entity instanceof EnemyProxy proxy?BoatPassengers.board(cart,proxy):original.call(entity,cart);
    }
}
