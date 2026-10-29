package dev.sifucraft.client;

import dev.sifucraft.combat.EnemyMirrors;
import java.nio.file.Files;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Regression for legacy peaceful development saves; only runs in disposable fixture worlds. */
final class SurvivalDifficultyCheck {
    private boolean done;

    static void prepare(MinecraftServer server) {
        if (!DevWorld.LEVEL_ID.startsWith("sifucraft-survival-check-"))
            throw new IllegalStateException("Survival regression requires a disposable fixture world");
        if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("sifucraft-survival-difficulty-v1")))
            throw new IllegalStateException("Survival fixture must not already have a migration marker");
        server.setDifficulty(Difficulty.PEACEFUL, true);
    }

    void tick(MinecraftServer server) {
        if (done || server.getPlayerList().getPlayers().isEmpty()) return;
        var target = EnemyMirrors.testTarget(101);
        if (target == null) return;
        var player = server.getPlayerList().getPlayers().getFirst();
        if (player.tickCount < 100) return; // vanilla login protection is still active initially
        if (server.overworld().getDifficulty() != Difficulty.NORMAL)
            throw new AssertionError("Legacy peaceful world did not migrate to normal");
        try {
            var saved = net.minecraft.nbt.NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("level.dat"),
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            String difficulty = saved.getCompoundOrEmpty("Data").getCompoundOrEmpty("difficulty_settings")
                    .getStringOr("difficulty", "missing");
            if (!"normal".equals(difficulty)) throw new AssertionError("Marker completed before normal difficulty was saved: " + difficulty);
        } catch (java.io.IOException e) { throw new AssertionError("Could not verify persisted migration", e); }
        server.setDifficulty(Difficulty.HARD, true);
        DevWorld.migrateDifficulty(server);
        if (server.overworld().getDifficulty() != Difficulty.HARD)
            throw new AssertionError("Migration overwrote a later difficulty choice");
        server.setDifficulty(Difficulty.NORMAL, true);
        float before = player.getHealth();
        boolean accepted = player.hurtServer(player.level(), player.damageSources().mobAttack(target), 2);
        if (!accepted || Math.abs(before - player.getHealth() - 2) > .001f)
            throw new AssertionError("Vanilla incoming mob attack rejected after migration: HP=" + player.getHealth());
        boolean repeated = player.hurtServer(player.level(), player.damageSources().mobAttack(target), 2);
        if (repeated || Math.abs(before - player.getHealth() - 2) > .001f)
            throw new AssertionError("Vanilla hurt immunity changed");
        done = true;
        LoggerFactory.getLogger("sifucraft").info("[survival-difficulty-test] PASS: legacy PEACEFUL -> NORMAL, " +
                "normal persisted before marker, marker preserves later HARD choice, vanilla mob attack 20 -> 18, immediate duplicate rejected");
    }
}
