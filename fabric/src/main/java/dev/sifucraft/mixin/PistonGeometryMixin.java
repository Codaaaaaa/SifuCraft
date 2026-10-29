package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.blocks.ShapeChanges;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.PistonTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonGeometryMixin {
    @WrapMethod(method="tick")
    private static void sifucraft$geometryRevision(Level level,BlockPos pos,BlockState state,PistonMovingBlockEntity piston,Operation<Void> original){
        var progress=PistonTimeline.progress(piston);
        if(progress!=null){var access=(PistonAccess)piston;access.sifucraft$progress(progress);access.sifucraft$previousProgress(progress);return;}
        float before=piston.getProgress(1);
        dev.sifucraft.blocks.PistonMachines.tick(level,pos,piston,()->original.call(level,pos,state,piston));
        if(WorldBlocks.ENABLED&&level instanceof ServerLevel&&before!=piston.getProgress(1))ShapeChanges.changed();
        if(level instanceof ServerLevel)PistonTimeline.updated(piston);
    }
    @Inject(method="getProgress",at=@At("HEAD"),cancellable=true)
    private void sifucraft$physicalProgress(float partial,CallbackInfoReturnable<Float> cir){
        var progress=PistonTimeline.progress((PistonMovingBlockEntity)(Object)this);if(progress!=null)cir.setReturnValue(progress);
    }
}
