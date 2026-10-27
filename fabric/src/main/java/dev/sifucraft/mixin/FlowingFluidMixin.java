package dev.sifucraft.mixin;

import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void sifucraft$deferOtherMap(ServerLevel level,BlockPos pos,BlockState block,FluidState fluid,CallbackInfo ci){
        if(!SceneFluidGeometry.inActiveMap(level,pos)){level.scheduleTick(pos,fluid.getType(),20);ci.cancel();}
    }
    // Run after the original state-only cache. Native results never enter that
    // cache, so two AIR/AIR pairs with different scene geometry stay independent.
    @Inject(method="canPassThroughWall",at=@At("RETURN"),cancellable=true)
    private static void sifucraft$nativeFluidFaces(Direction face,BlockGetter level,BlockPos from,BlockState a,BlockPos to,BlockState b,CallbackInfoReturnable<Boolean> cir){
        if(cir.getReturnValueZ()&&!SceneFluidGeometry.canPass(face,level,from,a,to,b))cir.setReturnValue(false);
    }
    @Inject(method="canHoldSpecificFluid",at=@At("RETURN"),cancellable=true)
    private static void sifucraft$nativeFluidCell(BlockGetter level,BlockPos pos,BlockState state,Fluid fluid,CallbackInfoReturnable<Boolean> cir){
        if(cir.getReturnValueZ()&&!SceneFluidGeometry.canOccupy(level,pos))cir.setReturnValue(false);
    }
}
