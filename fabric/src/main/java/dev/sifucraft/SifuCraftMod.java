package dev.sifucraft;

import dev.sifucraft.combat.EnemyEntities;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.blocks.WorldBlocks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

/** Registers common entity data before the registries freeze; this mod still runs client-only. */
public final class SifuCraftMod implements ModInitializer {
    @Override public void onInitialize() {
        EnemyEntities.register();
        dev.sifucraft.blocks.SignalEntities.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> dev.sifucraft.blocks.PlayerSignalContacts.stop());
        if (WorldBlocks.ENABLED) {
            ServerLifecycleEvents.SERVER_STARTING.register(server -> WorldBlocks.open());
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> WorldBlocks.close());
            ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> WorldBlocks.loaded(level, chunk));
            ServerChunkEvents.CHUNK_UNLOAD.register(WorldBlocks::unloaded);
        }
        ServerTickEvents.END_SERVER_TICK.register(EnemyMirrors::tick);
        ServerTickEvents.START_SERVER_TICK.register(dev.sifucraft.blocks.PlayerSignalContacts::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> dev.sifucraft.blocks.PlayerSignalContacts.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EnemyMirrors.stop());
    }
}
