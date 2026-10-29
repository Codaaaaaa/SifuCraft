package dev.sifucraft.client.mixin;

import dev.sifucraft.client.NewPlayerSetup;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerDataMixin {
    @Inject(method = "loadPlayerData", at = @At("RETURN"))
    private void sifucraft$loaded(NameAndId player, CallbackInfoReturnable<Optional<CompoundTag>> ci) {
        NewPlayerSetup.loaded(player.id(), ci.getReturnValue().isPresent());
    }
}
