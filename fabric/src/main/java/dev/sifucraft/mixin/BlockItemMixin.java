package dev.sifucraft.mixin;

import dev.sifucraft.blocks.SceneBlockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reject the placed shape intersecting Sifu geometry, including a door's second half.
 * Checked on the client and on the server alike (both see the same collision).
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void sifucraft$notInsideSifu(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        BlockPos pos = context.getClickedPos();
        Level level=context.getLevel();
        if (SceneBlockSupport.intersects(level,pos,state) || state.getBlock() instanceof DoorBlock &&
            SceneBlockSupport.intersects(level,pos.above(),state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER)))
            cir.setReturnValue(false);
    }
}
