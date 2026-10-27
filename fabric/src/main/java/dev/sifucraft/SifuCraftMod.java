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
        dev.sifucraft.blocks.PistonActionTickets.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> dev.sifucraft.blocks.PlayerSignalContacts.stop());
        if (WorldBlocks.ENABLED) {
            ServerLifecycleEvents.SERVER_STARTING.register(server -> WorldBlocks.open());
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> WorldBlocks.close());
            ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> WorldBlocks.loaded(level, chunk));
            ServerChunkEvents.CHUNK_UNLOAD.register(WorldBlocks::unloaded);
        }
        ServerTickEvents.START_SERVER_TICK.register(EnemyMirrors::tick);
        ServerTickEvents.END_SERVER_TICK.register(dev.sifucraft.blocks.PistonMachines::update);
        ServerTickEvents.END_SERVER_TICK.register(dev.sifucraft.blocks.PistonActionTickets::tick);
        // The explicit environment fixture tests NativeFree H7/F8/pause against
        // a stationary reference. Continuous motion has separate differential
        // and real-capsule fixtures; letting it float this reference target away
        // would change the damage fixture's prescribed input.
        if(!Boolean.getBoolean("sifucraft.environmentDamageSelfTest"))ServerTickEvents.END_SERVER_TICK.register(dev.sifucraft.combat.FluidMotion::tick);
        ServerTickEvents.START_SERVER_TICK.register(dev.sifucraft.blocks.PlayerSignalContacts::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> dev.sifucraft.blocks.PlayerSignalContacts.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EnemyMirrors.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> dev.sifucraft.blocks.PistonMachines.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> dev.sifucraft.blocks.PistonActionTickets.stop());
        // stopServer saves chunk BEs AFTER SERVER_STOPPING. Keep their action
        // membership available to the NBT mixin until the final save has ended.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> dev.sifucraft.blocks.PistonActions.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> dev.sifucraft.combat.FluidMotion.stop());
    }
}
