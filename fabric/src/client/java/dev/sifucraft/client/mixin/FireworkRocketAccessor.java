package dev.sifucraft.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Flight observation and an isolated rendering fixture's long-lived rocket. */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketAccessor {
    @Accessor("attachedToEntity") LivingEntity sifucraft$attachedToEntity();
    @Accessor("lifetime") void sifucraft$setLifetime(int ticks);
}
