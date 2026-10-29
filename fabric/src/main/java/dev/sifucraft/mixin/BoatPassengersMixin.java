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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoat.class)
public abstract class BoatPassengersMixin {
    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method="getDismountLocationForPassenger",at=@At("RETURN"))
    private net.minecraft.world.phys.Vec3 sifucraft$nativePassengerDismount(net.minecraft.world.phys.Vec3 vanilla,net.minecraft.world.entity.LivingEntity passenger){
        return BoatPassengers.dismount((AbstractBoat)(Object)this,passenger,vanilla);
    }
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void sifucraft$waitForCoverage(CallbackInfo ci){
        var boat=(AbstractBoat)(Object)this;
        if(!dev.sifucraft.blocks.SceneFluidGeometry.knownVolume(boat.level(),boat.getBoundingBox().expandTowards(boat.getDeltaMovement()).inflate(1)))ci.cancel();
    }
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
