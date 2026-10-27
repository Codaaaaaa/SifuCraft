package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LavaFluid.class)
public abstract class LavaSceneMixin {
    @Inject(method="randomTick",at=@At("HEAD"),cancellable=true)
    private void sifucraft$knownLava(ServerLevel level,BlockPos source,FluidState fluid,RandomSource random,CallbackInfo ci){
        if(!SceneFluidGeometry.inActiveMap(level,source))ci.cancel();
    }
    @Inject(method="isFlammable",at=@At("HEAD"),cancellable=true)
    private void sifucraft$knownFuel(LevelReader level,BlockPos fuel,CallbackInfoReturnable<Boolean> cir){
        if(!SceneFluidGeometry.canOccupy(level,fuel))cir.setReturnValue(false);
    }
    @WrapOperation(method="randomTick",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean sifucraft$knownFire(ServerLevel level,BlockPos target,BlockState state,Operation<Boolean> original,@Local(argsOnly=true) BlockPos source){
        return SceneFluidGeometry.canEmitTo(level,source,target)&&original.call(level,target,state);
    }
}
