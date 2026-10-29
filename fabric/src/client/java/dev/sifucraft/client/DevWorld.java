package dev.sifucraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Development only ({@code -Dsifucraft.devWorld=true}): enters a survival superflat world straight
 * from the title screen, creating it on first use, so bridge tests need no clicking in Minecraft.
 */
final class DevWorld {
    static final String LEVEL_ID = System.getProperty("sifucraft.devWorldName", "sifucraft-dev");

    private DevWorld() {}

    static boolean enabled() {
        return Boolean.getBoolean("sifucraft.devWorld");
    }

    /** Old development saves predate survival and used peaceful to suppress vanilla mobs. */
    static void migrateDifficulty(MinecraftServer server) {
        if (!enabled()) return;
        var marker = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("sifucraft-survival-difficulty-v1");
        if (java.nio.file.Files.exists(marker)) return;
        boolean migrated = server.overworld().getDifficulty() == Difficulty.PEACEFUL;
        if (migrated) {
            // Peaceful previously suppressed mobs in this otherwise invisible backing world.
            server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            server.setDifficulty(Difficulty.NORMAL, true);
            org.slf4j.LoggerFactory.getLogger("sifucraft").info(
                    "[world] migrated legacy development difficulty PEACEFUL -> NORMAL, natural mob spawning disabled; vanilla incoming mob damage restored");
        }
        try {
            if (migrated && !server.saveEverything(true, true, false))
                throw new java.io.IOException("World save did not complete; migration marker deferred");
            java.nio.file.Files.writeString(marker, "Legacy survival difficulty checked; later difficulty choices are preserved.\n");
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger("sifucraft").warn("[world] could not save difficulty migration marker", e);
        }
    }

    /** Blocks shown in Sifu are lit by Minecraft's sky: keep it at noon instead of following the day cycle. */
    static void lockDaytime(MinecraftServer server) {
        if (!enabled()) return;
        var clock = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
                .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
        server.clockManager().moveToTimeMarker(clock, net.minecraft.world.clock.ClockTimeMarkers.NOON);
        server.clockManager().setPaused(clock, true);
    }

    static void enter(Minecraft minecraft) {
        if (minecraft.getLevelSource().levelExists(LEVEL_ID)) {
            minecraft.createWorldOpenFlows().openWorld(LEVEL_ID, () -> minecraft.gui.setScreen(new TitleScreen()));
            return;
        }
        LevelSettings settings = new LevelSettings("SifuCraft Dev", GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true,
                WorldDataConfiguration.DEFAULT);
        // The standard superflat preset: anything else counts as "customized" and every later open
        // would stop at a backup confirmation screen.
        minecraft.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, new WorldOptions(0L, false, false),
                registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                        .createWorldDimensions(),
                new TitleScreen());
    }
}
