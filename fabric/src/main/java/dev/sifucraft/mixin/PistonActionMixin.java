package dev.sifucraft.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.blocks.PistonActions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(PistonBaseBlock.class)
public abstract class PistonActionMixin {
    @WrapMethod(method="triggerEvent")
    private boolean sifucraft$captureWholeAction(BlockState state,Level level,BlockPos pos,int type,int data,Operation<Boolean> original){return PistonActions.event(level,pos,()->original.call(state,level,pos,type,data));}
}
