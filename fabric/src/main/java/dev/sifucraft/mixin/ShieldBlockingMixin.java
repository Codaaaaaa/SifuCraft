package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sifucraft.combat.ShieldBlocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class ShieldBlockingMixin {
    @Inject(method = "getItemBlockingWith", at = @At("RETURN"), cancellable = true)
    private void sifucraft$perfectRaise(CallbackInfoReturnable<ItemStack> cir) {
        if (cir.getReturnValue() != null) return;
        ItemStack item = ShieldBlocks.perfectItem((LivingEntity) (Object) this);
        if (item != null) cir.setReturnValue(item);
    }

    @Inject(method = "applyItemBlocking", at = @At("RETURN"))
    private void sifucraft$actualBlock(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Float> cir) {
        ShieldBlocks.captured((LivingEntity) (Object) this, source, cir.getReturnValue());
    }

    @WrapOperation(method = "applyItemBlocking", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/item/component/BlocksAttacks;hurtBlockingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;F)V"))
    private void sifucraft$perfectWear(BlocksAttacks component, Level level, ItemStack item, LivingEntity entity,
                                      InteractionHand hand, float blocked, Operation<Void> original,
                                      @Local(argsOnly = true) DamageSource source) {
        if (!ShieldBlocks.preserveDurability(entity, source)) original.call(component, level, item, entity, hand, blocked);
        else if (entity instanceof net.minecraft.server.level.ServerPlayer player)
            player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(item.getItem()));
    }
}
