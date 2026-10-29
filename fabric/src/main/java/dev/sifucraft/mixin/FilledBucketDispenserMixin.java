package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Installed 26.3 registration: shared water/lava/powder-snow/mob bucket behavior. */
@Mixin(targets="net.minecraft.core.dispenser.DispenseItemBehavior$3")
public abstract class FilledBucketDispenserMixin {
    @WrapOperation(method="execute",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/DispensibleContainerItem;emptyContents(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Z"))
    private boolean sifucraft$nativeFace(DispensibleContainerItem item,LivingEntity user,Level level,BlockPos pos,BlockHitResult hit,Operation<Boolean> original,@Local(argsOnly=true) BlockSource source){
        return SceneFluidGeometry.canDispenseTo(level,source.pos(),pos)&&original.call(item,user,level,pos,hit);
    }
}
