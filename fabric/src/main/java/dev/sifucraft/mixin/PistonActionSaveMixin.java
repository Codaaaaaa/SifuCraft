package dev.sifucraft.mixin;
import dev.sifucraft.blocks.PistonActions;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonActionSaveMixin {
    @Inject(method="saveAdditional",at=@At("TAIL"))
    private void sifucraft$save(ValueOutput output,CallbackInfo ci){PistonActions.save((PistonMovingBlockEntity)(Object)this,output);}
    @Inject(method="loadAdditional",at=@At("TAIL"))
    private void sifucraft$load(ValueInput input,CallbackInfo ci){PistonActions.load((PistonMovingBlockEntity)(Object)this,input);}
}
