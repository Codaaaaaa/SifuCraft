package dev.sifucraft.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("mouseGrabbed")
    void sifucraft$setMouseGrabbed(boolean grabbed);
    @Accessor("accumulatedDX") void sifucraft$setAccumulatedDX(double value);
    @Accessor("accumulatedDY") void sifucraft$setAccumulatedDY(double value);
    @Accessor("ignoreFirstMove") void sifucraft$setIgnoreFirstMove(boolean value);
}
