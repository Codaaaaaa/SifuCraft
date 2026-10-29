package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sifucraft.combat.BlockEnvironment;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Ordinary entity calls forward unchanged; only the scoped receipt pass filters callbacks. */
@Mixin(Entity.class)
public abstract class EnvironmentPathMixin {
    @Shadow @Final private LongSet visitedBlocks;
    @Shadow private int checkInsideBlocks(Vec3 from,Vec3 to,InsideBlockEffectApplier.StepBasedCollector collector,LongSet visited,int maxSteps){throw new AssertionError();}
    @WrapOperation(method="applyEffectsFromBlocks(Ljava/util/List;)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;checkInsideBlocks(Ljava/util/List;Lnet/minecraft/world/entity/InsideBlockEffectApplier$StepBasedCollector;)V"))
    private void sifucraft$receiptedPath(Entity target,List<?> movements,InsideBlockEffectApplier.StepBasedCollector collector,Operation<Void> original){
        var path=BlockEnvironment.path(target);
        if(path!=null){int step=0;for(var movement:path){collector.advanceStep(--step);checkInsideBlocks(movement.from(),movement.to(),collector,visitedBlocks,16);}}
        original.call(target,movements,collector); // installed collector flush, visited reset and fire/rain post-processing
    }
    @WrapOperation(method="lambda$checkInsideBlocks$0",at=@At(value="INVOKE",target="Lit/unimi/dsi/fastutil/longs/LongSet;add(J)Z"))
    private boolean sifucraft$visitActualMedium(LongSet visited,long pos,Operation<Boolean> original,@Local(argsOnly=true,ordinal=0) Vec3 from,@Local(argsOnly=true,ordinal=1) Vec3 to){
        return BlockEnvironment.mayVisit((Entity)(Object)this,BlockPos.of(pos),from,to)&&original.call(visited,pos);
    }
    @WrapOperation(method="lambda$checkInsideBlocks$0",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;entityInside(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;Z)V"))
    private void sifucraft$environmentBlocks(BlockState state,Level level,BlockPos pos,Entity target,InsideBlockEffectApplier collector,boolean intersects,Operation<Void> original){
        if(BlockEnvironment.block(target,pos,state))original.call(state,level,pos,target,collector,intersects);
    }
    @WrapOperation(method="lambda$checkInsideBlocks$0",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/material/FluidState;entityInside(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;)V"))
    private void sifucraft$environmentFluids(FluidState fluid,Level level,BlockPos pos,Entity target,InsideBlockEffectApplier collector,Operation<Void> original){
        if(BlockEnvironment.fluid(target,pos,fluid))original.call(fluid,level,pos,target,collector);
    }
    @WrapOperation(method="applyEffectsFromBlocks(Ljava/util/List;)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/Block;stepOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V"))
    private void sifucraft$noUnrelatedSteps(Block block,Level level,BlockPos pos,BlockState state,Entity target,Operation<Void> original){
        if(!BlockEnvironment.active(target))original.call(block,level,pos,state,target);
    }
}
