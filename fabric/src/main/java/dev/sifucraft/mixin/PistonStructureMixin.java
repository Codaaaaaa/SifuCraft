package dev.sifucraft.mixin;

import dev.sifucraft.blocks.PistonSceneGuard;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private BlockPos pistonPos;
    @Shadow @Final private Direction pistonDirection;
    @Shadow @Final private Direction pushDirection;
    @Shadow @Final private boolean extending;
    @Shadow @Final private List<BlockPos> toPush;
    @Inject(method="resolve",at=@At("RETURN"),cancellable=true)
    private void sifucraft$stopAtSceneGeometry(CallbackInfoReturnable<Boolean> cir){
        if(cir.getReturnValueZ()&&!PistonSceneGuard.allows(level,pistonPos,pistonDirection,extending,pushDirection,toPush))cir.setReturnValue(false);
    }
}
