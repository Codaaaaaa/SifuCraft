package dev.sifucraft.mixin;
import dev.sifucraft.combat.MachineProjectiles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Projectile.class)
public abstract class ProjectileSourceMixin implements MachineProjectiles.Carrier {
    @Unique private MachineProjectiles.Origin sifucraft$origin;
    @Override public MachineProjectiles.Origin sifucraft$machineOrigin(){return sifucraft$origin;}
    @Override public void sifucraft$machineOrigin(MachineProjectiles.Origin value){sifucraft$origin=value;}
    @Inject(method="spawnProjectile(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;)Lnet/minecraft/world/entity/projectile/Projectile;",at=@At("RETURN"))
    private static <T extends Projectile> void sifucraft$recordSource(T projectile,ServerLevel level,ItemStack stack,Consumer<T> launch,CallbackInfoReturnable<T> cir){if(cir.getReturnValue()!=null)MachineProjectiles.created(cir.getReturnValue());}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void sifucraft$saveOrigin(ValueOutput output,CallbackInfo ci){MachineProjectiles.save(sifucraft$origin,output);}
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void sifucraft$loadOrigin(ValueInput input,CallbackInfo ci){sifucraft$origin=MachineProjectiles.load(input);}
}
