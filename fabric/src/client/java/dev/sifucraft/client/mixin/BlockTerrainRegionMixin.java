package dev.sifucraft.client.mixin;

import dev.sifucraft.blocks.PlacedBlocks;
import dev.sifucraft.client.WorldTerrain;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rendering-only view: the backing superflat floor never enters the Sifu world image. */
@Mixin(RenderSectionRegion.class)
public abstract class BlockTerrainRegionMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void sifucraft$omitBackingFloor(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (WorldTerrain.hideBackingFloor() && pos.getY() < PlacedBlocks.MIN_Y) cir.setReturnValue(Blocks.AIR.defaultBlockState());
    }
}
