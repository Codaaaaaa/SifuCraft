package dev.sifucraft.client.mixin;
import dev.sifucraft.client.PistonTicketsCheck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonTicketDelayMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private static void sifucraft$isolatedChunkDelay(Level level,BlockPos pos,BlockState state,PistonMovingBlockEntity piston,CallbackInfo ci){if(PistonTicketsCheck.hold(level,pos))ci.cancel();}
}
