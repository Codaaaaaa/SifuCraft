package dev.sifucraft.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftUseAccessor {
    @Invoker("startUseItem") void sifucraft$startUseItem();
}
