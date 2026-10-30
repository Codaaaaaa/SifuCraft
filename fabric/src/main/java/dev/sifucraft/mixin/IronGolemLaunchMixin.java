package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.combat.WorldEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A golem's hit on a Sifu NPC is softened, then tosses it like a vanilla mob: the hit's 0.4 knockback
 * away from the golem plus the golem's own 0.4 upward fling. Sifu owns the NPC, so the toss is a native flight. */
@Mixin(IronGolem.class)
public abstract class IronGolemLaunchMixin {
    // ponytail: fixed share of vanilla's 7.5-22.5 golem damage; make it a launch property if it needs tuning in play
    @Unique private static final float NPC_DAMAGE_SCALE = .1f;

    /** Native cancels a toss when new damage lands, and the hit reaction delays the toss's lease by ~0.9 s:
     * the golem holds its next swing until the NPC is airborne and has landed. */
    @Inject(method="doHurtTarget",at=@At("HEAD"),cancellable=true)
    private void sifucraft$waitForToss(ServerLevel level,Entity target,CallbackInfoReturnable<Boolean> cir){
        if(target instanceof EnemyProxy proxy&&dev.sifucraft.combat.FluidMotion.moving(proxy.sifuKey()))cir.setReturnValue(false);
    }

    @WrapOperation(method="doHurtTarget",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean sifucraft$softer(Entity target,ServerLevel level,DamageSource source,float amount,Operation<Boolean> original){
        return original.call(target,level,source,target instanceof EnemyProxy?amount*NPC_DAMAGE_SCALE:amount);
    }
    @Inject(method="doHurtTarget",at=@At("RETURN"))
    private void sifucraft$toss(ServerLevel level,Entity target,CallbackInfoReturnable<Boolean> cir){
        if(!cir.getReturnValueZ()||!(target instanceof EnemyProxy proxy))return;
        var golem=(IronGolem)(Object)this;
        var away=new Vec3(proxy.getX()-golem.getX(),0,proxy.getZ()-golem.getZ());
        away=away.lengthSqr()<1e-6?Vec3.ZERO:away.normalize().scale(.4);
        WorldEffects.golemToss(proxy,away.add(0,.8,0));
    }
}
