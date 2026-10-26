package dev.sifucraft.mixin;

import dev.sifucraft.blocks.SceneBlockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockSupportMixin {
    @Inject(method="getBlockSupportShape",at=@At("RETURN"),cancellable=true)
    private void sifucraft$sceneSupport(BlockGetter level,BlockPos pos,CallbackInfoReturnable<VoxelShape> cir) {
        cir.setReturnValue(SceneBlockSupport.supportShape(level,pos,cir.getReturnValue()));
    }
    // Vanilla AIR has a cached false answer, so the shape hook alone is insufficient.
    @Inject(method="isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z",
        at=@At("RETURN"),cancellable=true)
    private void sifucraft$dynamicFace(BlockGetter level,BlockPos pos,Direction face,SupportType type,CallbackInfoReturnable<Boolean> cir) {
        if(!cir.getReturnValueZ()&&!SceneBlockSupport.nativeShape(level,pos).isEmpty())
            cir.setReturnValue(type.isSupporting((BlockState)(Object)this,level,pos,face));
    }
}
