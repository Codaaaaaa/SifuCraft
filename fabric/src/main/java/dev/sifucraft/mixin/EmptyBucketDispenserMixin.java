package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Includes liquid blocks, powder snow and original waterlogged BucketPickup implementations. */
@Mixin(targets="net.minecraft.core.dispenser.DispenseItemBehavior$4")
public abstract class EmptyBucketDispenserMixin {
    @WrapOperation(method="execute",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack sifucraft$nativeFace(BucketPickup pickup,LivingEntity user,LevelAccessor level,BlockPos pos,BlockState state,Operation<ItemStack> original,@Local(argsOnly=true) BlockSource source){
        return SceneFluidGeometry.canDispenseTo(source.level(),source.pos(),pos)?original.call(pickup,user,level,pos,state):ItemStack.EMPTY;
    }
}
