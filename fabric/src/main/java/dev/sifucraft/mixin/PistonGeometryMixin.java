package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.blocks.ShapeChanges;
import dev.sifucraft.blocks.WorldBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonGeometryMixin {
    @WrapMethod(method="tick")
    private static void sifucraft$geometryRevision(Level level,BlockPos pos,BlockState state,PistonMovingBlockEntity piston,Operation<Void> original){
        float before=piston.getProgress(1);
        original.call(level,pos,state,piston);
        if(WorldBlocks.ENABLED&&level instanceof ServerLevel&&before!=piston.getProgress(1))ShapeChanges.changed();
    }
}
