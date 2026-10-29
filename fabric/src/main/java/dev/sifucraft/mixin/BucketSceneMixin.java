package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class BucketSceneMixin {
    @WrapOperation(method="use",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/BucketItem;getPlayerPOVHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult sifucraft$bucketSceneRay(Level level,Player player,ClipContext.Fluid fluid,Operation<BlockHitResult> original){
        return SceneFluidGeometry.bucketRay(level,player,original.call(level,player,fluid));
    }
    @Inject(method="emptyContents",at=@At("HEAD"),cancellable=true)
    private void sifucraft$bucketOccupancy(LivingEntity user,Level level,BlockPos pos,BlockHitResult hit,CallbackInfoReturnable<Boolean> cir){
        if(!SceneFluidGeometry.canOccupy(level,pos))cir.setReturnValue(false);
    }
}
