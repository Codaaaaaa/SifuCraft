package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.blocks.PistonContacts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
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
}
