package dev.sifucraft.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only observation for the opt-in flight check. */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketAccessor {
    @Accessor("attachedToEntity") LivingEntity sifucraft$attachedToEntity();
}
