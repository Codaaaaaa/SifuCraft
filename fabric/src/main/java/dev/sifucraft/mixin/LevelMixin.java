package dev.sifucraft.mixin;

import dev.sifucraft.blocks.PlacedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block the server sets in the overworld updates the blocks Sifu mirrors. */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void sifucraft$trackPlacedBlocks(BlockPos pos, BlockState state, int flags, int recursion,
            CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (!cir.getReturnValueZ() || self.isClientSide() || self.dimension() != Level.OVERWORLD) return;
        BlockState now = self.getBlockState(pos);
        PlacedBlocks.changed(pos, now, now.isCollisionShapeFullBlock(self, pos));
    }
}
