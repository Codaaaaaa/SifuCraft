package dev.sifucraft.client.mixin;

import dev.sifucraft.client.SifuCraftClient;
import dev.sifucraft.client.WorldEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One vanilla Steve skin for both the first-person hands and the third-person wide-arm renderer. */
@Mixin(AbstractClientPlayer.class)
public abstract class AvatarSkinMixin {
    @Unique private static final ClientAsset.Texture SIFUCRAFT_STEVE =
            new ClientAsset.ResourceTexture(Identifier.withDefaultNamespace("entity/player/wide/steve"));

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void sifucraft$localSteve(CallbackInfoReturnable<PlayerSkin> cir) {
        if (!SifuCraftClient.minecraftOwnsInput() || !WorldEffects.active() ||
                (Object)this != Minecraft.getInstance().player) return;
        var skin = cir.getReturnValue();
        cir.setReturnValue(new PlayerSkin(SIFUCRAFT_STEVE, skin.cape(), skin.elytra(), PlayerModelType.WIDE, skin.secure()));
    }
}
