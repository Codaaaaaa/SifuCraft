package dev.sifucraft.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.combat.MachineProjectiles;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(DispenserBlock.class)
public abstract class DispenserSourceMixin {
    @WrapMethod(method="dispenseFrom")
    private void sifucraft$source(ServerLevel level,BlockState state,BlockPos pos,Operation<Void> original){MachineProjectiles.dispense(level,pos,()->original.call(level,state,pos));}
}
