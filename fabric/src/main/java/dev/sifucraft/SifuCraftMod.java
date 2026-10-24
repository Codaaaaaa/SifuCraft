package dev.sifucraft;

import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyMirrors;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Registers common entity data before the registries freeze; this mod still runs client-only. */
public final class SifuCraftMod implements ModInitializer {
    @Override public void onInitialize() {
        EnemyEntities.register();
        ServerTickEvents.END_SERVER_TICK.register(EnemyMirrors::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EnemyMirrors.stop());
    }
}
