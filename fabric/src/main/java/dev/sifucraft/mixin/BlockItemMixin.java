package dev.sifucraft.mixin;

import dev.sifucraft.collision.SifuCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No block whose centre is inside Sifu's geometry: it would be buried in a wall or a floor in Sifu.
 * Checked on the client and on the server alike (both see the same collision).
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void sifucraft$notInsideSifu(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        SifuCollision.Active active = SifuCollision.active();
        if (active == null || context.getLevel().dimension() != Level.OVERWORLD) return;
        BlockPos pos = context.getClickedPos();
        if (active.solidAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) cir.setReturnValue(false);
    }
}
