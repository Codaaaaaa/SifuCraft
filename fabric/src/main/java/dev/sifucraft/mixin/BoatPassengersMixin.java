package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.sifucraft.combat.BoatPassengers;
import dev.sifucraft.combat.EnemyProxy;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractBoat.class)
public abstract class BoatPassengersMixin {
    @WrapMethod(method="positionRider")
    private void sifucraft$actualHitMirror(Entity passenger,Entity.MoveFunction move,Operation<Void> original){
        if(BoatPassengers.positionRider(passenger))original.call(passenger,move);
    }
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> sifucraft$nearby(Level level,Entity boat,AABB box,Predicate<Entity> predicate,Operation<List<Entity>> original){
        return BoatPassengers.candidates((AbstractBoat)boat,box,original.call(level,boat,box,predicate));
    }
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;startRiding(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean sifucraft$reserve(Entity entity,Entity boat,Operation<Boolean> original){
        return entity instanceof EnemyProxy proxy?BoatPassengers.board((AbstractBoat)boat,proxy):original.call(entity,boat);
    }
}
