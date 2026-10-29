package dev.sifucraft.mixin;
import dev.sifucraft.blocks.PistonActions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MovingPistonBlock.class)
public abstract class PistonMembershipMixin {
    @Inject(method="newMovingBlockEntity",at=@At("RETURN"))
    private static void sifucraft$created(BlockPos pos,BlockState state,BlockState moved,Direction direction,boolean extending,boolean source,CallbackInfoReturnable<BlockEntity> cir){PistonActions.created(cir.getReturnValue());}
}
