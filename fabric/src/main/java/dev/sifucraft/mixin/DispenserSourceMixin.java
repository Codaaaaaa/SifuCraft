package dev.sifucraft.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sifucraft.combat.MachineProjectiles;
import dev.sifucraft.blocks.SceneFluidGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(DispenserBlock.class)
public abstract class DispenserSourceMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void sifucraft$pendingBuckets(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){
        if(!state.is(Blocks.DISPENSER)||SceneFluidGeometry.available(level)&&SceneFluidGeometry.inActiveMap(level,pos)||!(level.getBlockEntity(pos) instanceof DispenserBlockEntity dispenser))return;
        for(int slot=0;slot<dispenser.getContainerSize();slot++)if(dispenser.getItem(slot).getItem() instanceof DispensibleContainerItem){
            if(Boolean.getBoolean("sifucraft.fluidSceneSelfTest"))org.slf4j.LoggerFactory.getLogger("sifucraft").info("[dispenser-fluid-test] defer source={} available={} item={}",pos,SceneFluidGeometry.available(level),dispenser.getItem(slot));
            level.scheduleTick(pos,state.getBlock(),SceneFluidGeometry.available(level)?20:1);ci.cancel();return;
        }
    }
    @WrapMethod(method="dispenseFrom")
    private void sifucraft$source(ServerLevel level,BlockState state,BlockPos pos,Operation<Void> original){MachineProjectiles.dispense(level,pos,()->original.call(level,state,pos));}
}
