package dev.sifucraft.mixin;

import dev.sifucraft.combat.MachineProjectiles;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AreaEffectCloud.class)
public abstract class CloudSourceMixin implements MachineProjectiles.Carrier {
    @Unique private MachineProjectiles.Origin sifucraft$origin;
    @Override public MachineProjectiles.Origin sifucraft$machineOrigin(){return sifucraft$origin;}
    @Override public void sifucraft$machineOrigin(MachineProjectiles.Origin origin){sifucraft$origin=origin;}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void sifucraft$saveOrigin(ValueOutput output,CallbackInfo ci){MachineProjectiles.save(sifucraft$origin,output);}
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void sifucraft$loadOrigin(ValueInput input,CallbackInfo ci){sifucraft$origin=MachineProjectiles.load(input);}
}
