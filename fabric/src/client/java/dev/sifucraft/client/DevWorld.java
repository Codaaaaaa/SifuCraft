package dev.sifucraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
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
