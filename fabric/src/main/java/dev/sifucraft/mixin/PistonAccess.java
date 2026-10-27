package dev.sifucraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PistonMovingBlockEntity.class)
public interface PistonAccess {
    @Invoker("moveCollidedEntities") static void sifucraft$collide(Level level,BlockPos pos,float next,PistonMovingBlockEntity piston){throw new AssertionError();}
    @Invoker("moveStuckEntities") static void sifucraft$carry(Level level,BlockPos pos,float next,PistonMovingBlockEntity piston){throw new AssertionError();}
    @Accessor("NOCLIP") static ThreadLocal<Direction> sifucraft$noClip(){throw new AssertionError();}
    @Accessor("progress") void sifucraft$progress(float value);
    @Accessor("progressO") void sifucraft$previousProgress(float value);
}
