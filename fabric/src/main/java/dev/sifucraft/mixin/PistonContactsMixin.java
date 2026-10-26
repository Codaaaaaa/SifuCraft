package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.blocks.PistonContacts;
import dev.sifucraft.blocks.PistonPreview;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import java.util.List;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonContactsMixin {
    @WrapMethod(method="moveCollidedEntities")
    private static void sifucraft$collided(Level level,BlockPos pos,float next,PistonMovingBlockEntity piston,Operation<Void> original){
        if(!PistonContacts.observing()){original.call(level,pos,next,piston);return;}
        PistonContacts.source(level,pos,next,piston,PistonContacts.Phase.COLLIDED,()->original.call(level,pos,next,piston));
    }
    @WrapMethod(method="moveStuckEntities")
    private static void sifucraft$carried(Level level,BlockPos pos,float next,PistonMovingBlockEntity piston,Operation<Void> original){
        if(!PistonContacts.observing()){original.call(level,pos,next,piston);return;}
        PistonContacts.source(level,pos,next,piston,PistonContacts.Phase.CARRIED,()->original.call(level,pos,next,piston));
    }
    @Redirect(method="moveEntityByPiston",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private static void sifucraft$observeMove(Entity entity,MoverType type,Vec3 movement){PistonContacts.move(entity,type,movement);}

    @WrapMethod(method="moveEntityByPiston")
    private static void sifucraft$restoreNoClip(Direction noClip,Entity entity,double distance,Direction movement,Operation<Void> original){
        var local=PistonAccess.sifucraft$noClip();var previous=local.get();
        try{original.call(noClip,entity,distance,movement);}finally{local.set(previous);}
    }
    @Redirect(method={"moveCollidedEntities","fixEntityWithinPistonBase"},at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private static AABB sifucraft$previewBox(Entity entity){return PistonPreview.box(entity);}
    @Redirect(method="moveCollidedEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 sifucraft$previewVelocity(Entity entity){return PistonPreview.velocity(entity);}
    @Redirect(method="moveCollidedEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;setDeltaMovement(DDD)V"))
    private static void sifucraft$previewSetVelocity(Entity entity,double x,double y,double z){PistonPreview.velocity(entity,x,y,z);}
    @Redirect(method="moveEntityByPiston",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;position()Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 sifucraft$previewPosition(Entity entity){return PistonPreview.position(entity);}
    @Redirect(method="moveEntityByPiston",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;applyEffectsFromBlocks(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"))
    private static void sifucraft$previewEffects(Entity entity,Vec3 from,Vec3 to){if(!PistonPreview.active())entity.applyEffectsFromBlocks(from,to);}
    @Redirect(method="moveEntityByPiston",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;removeLatestMovementRecording()V"))
    private static void sifucraft$previewRecording(Entity entity){if(!PistonPreview.active())entity.removeLatestMovementRecording();}
    @Redirect(method="moveCollidedEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private static List<Entity> sifucraft$previewEntities(Level level,Entity excluded,AABB area){return PistonPreview.entities(level,excluded,area,null);}
    @Redirect(method="moveStuckEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<Entity> sifucraft$previewCarried(Level level,Entity excluded,AABB area,Predicate<Entity> predicate){return PistonPreview.entities(level,excluded,area,predicate);}
    @Redirect(method="matchesStickyCritera",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;onGround()Z"))
    private static boolean sifucraft$previewGround(Entity entity){return PistonPreview.grounded(entity);}
    @Redirect(method="matchesStickyCritera",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getX()D"))
    private static double sifucraft$previewX(Entity entity){return PistonPreview.position(entity).x;}
    @Redirect(method="matchesStickyCritera",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getZ()D"))
    private static double sifucraft$previewZ(Entity entity){return PistonPreview.position(entity).z;}
}
