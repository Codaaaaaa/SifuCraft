package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LiquidBlock.class)
public abstract class LiquidContactMixin {
    @Inject(method="shouldSpreadLiquid",at=@At("HEAD"),cancellable=true)
    private void sifucraft$deferOtherMap(Level level,BlockPos pos,BlockState state,CallbackInfoReturnable<Boolean> cir){
        if(!SceneFluidGeometry.inActiveMap(level,pos))cir.setReturnValue(true);
    }
    @WrapOperation(method="shouldSpreadLiquid",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState sifucraft$fluidContact(Level level,BlockPos queried,Operation<FluidState> original,@Local(argsOnly=true) BlockPos source){
        return SceneFluidGeometry.canContact(level,source,queried)?original.call(level,queried):Fluids.EMPTY.defaultFluidState();
    }
    @WrapOperation(method="shouldSpreadLiquid",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState sifucraft$solidContact(Level level,BlockPos queried,Operation<BlockState> original,@Local(argsOnly=true) BlockPos source){
        return SceneFluidGeometry.canContact(level,source,queried)?original.call(level,queried):Blocks.AIR.defaultBlockState();
    }
}
