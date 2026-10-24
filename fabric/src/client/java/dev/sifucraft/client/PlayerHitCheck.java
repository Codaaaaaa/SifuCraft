package dev.sifucraft.client;

import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.PlayerDamage;
import dev.sifucraft.combat.CombatHits;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in development setup for visible damage; saved world settings are restored at shutdown. */
final class PlayerHitCheck {
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private UUID playerId;
    private GameType oldMode;
    private Difficulty oldDifficulty;
    private boolean oldKeep, oldRegen, oldSpawns;
    private float oldHealth, oldAbsorption;
    private int ticks;
    private boolean done;
    private int initialEntityId, landedTicks;
    private ServerPlayer initialPlayer;
    private long deadAt;
    private final ArrayList<PlayerDamage.Applied> applied = new ArrayList<>();
    private final PracticeShield practiceShield = new PracticeShield();

    void tick(MinecraftServer server) {
        boolean selfTest = Boolean.getBoolean("sifucraft.playerHitSelfTest");
        boolean respawnTest = Boolean.getBoolean("sifucraft.respawnSelfTest");
        if ((selfTest || respawnTest) && EnemyMirrors.testTarget(101) == null) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        var player = server.getPlayerList().getPlayers().getFirst();
        if (playerId == null) {
            playerId = player.getUUID(); oldMode = player.gameMode(); oldDifficulty = server.overworld().getDifficulty();
            initialEntityId = player.getId();
            initialPlayer = player;
            oldHealth = player.getHealth(); oldAbsorption = player.getAbsorptionAmount();
            var rules = server.getGameRules();
            oldKeep = rules.get(GameRules.KEEP_INVENTORY); oldRegen = rules.get(GameRules.NATURAL_HEALTH_REGENERATION);
            oldSpawns = rules.get(GameRules.SPAWN_MOBS);
            rules.set(GameRules.KEEP_INVENTORY, true, server);
            rules.set(GameRules.NATURAL_HEALTH_REGENERATION, false, server);
            rules.set(GameRules.SPAWN_MOBS, false, server);
            server.setDifficulty(Difficulty.NORMAL, true);
            player.setGameMode(GameType.SURVIVAL);
            player.setHealth(player.getMaxHealth()); player.setAbsorptionAmount(0);
            if (Boolean.getBoolean("sifucraft.shieldTestSupplies")) practiceShield.grantIfMissing(player);
            if (selfTest) PlayerDamage.diagnostic(applied::add);
            LOG.info("[player-hit-test] development survival/normal setup; inventory retained; original settings restored on shutdown");
        }
        if (selfTest && !done && ++ticks == 180) {
            if (applied.size() != 3 || Math.abs(player.getHealth() - 14) > 0.001f ||
                    !applied.get(0).accepted() || applied.get(1).accepted() || !applied.get(2).accepted()) {
                throw new AssertionError("player hit check failed: health=" + player.getHealth() + " events=" + applied);
            }
            done = true;
            PlayerDamage.diagnostic(null);
            LOG.info("[player-hit-test] PASS: native PlayerHit wire -> vanilla mobAttack; 20->18->18->14, " +
                    "duplicate suppressed, stale world/epoch and NaN rejected; applications={}", applied);
        }
        if (respawnTest && !done) {
            if (!player.isAlive() && deadAt == 0) deadAt = System.currentTimeMillis();
            var target = EnemyMirrors.testTarget(101);
            var scope = target == null ? null : CombatHits.canCapture(target) ? EnemyMirrors.damageScope(target) : null;
            if (deadAt != 0 && player.isAlive() && player != initialPlayer && scope != null && scope.epoch() > 1) {
                if (++landedTicks == 40) {
                    if (System.currentTimeMillis() - deadAt < 900 || Math.abs(player.getHealth() - 20) > 0.001f) {
                        throw new AssertionError("respawn delay or old-life hit check failed: health=" + player.getHealth());
                    }
                    done = true;
                    LOG.info("[respawn-test] PASS: delayed vanilla respawn, player object replaced (id {}->{}), new epoch={}, health=20; old-life hit rejected",
                            initialEntityId, player.getId(), scope.epoch());
                }
            }
        }
    }

    void restore(MinecraftServer server) {
        if (playerId == null) return;
        var rules = server.getGameRules();
        rules.set(GameRules.KEEP_INVENTORY, oldKeep, server);
        rules.set(GameRules.NATURAL_HEALTH_REGENERATION, oldRegen, server);
        rules.set(GameRules.SPAWN_MOBS, oldSpawns, server);
        server.setDifficulty(oldDifficulty, true);
        var player = server.getPlayerList().getPlayer(playerId);
        practiceShield.restore(server, player);
        if (player != null) { player.setGameMode(oldMode); player.setHealth(oldHealth); player.setAbsorptionAmount(oldAbsorption); }
        PlayerDamage.diagnostic(null);
        LOG.info("[player-hit-test] restored original mode, health, difficulty and game rules");
        playerId = null;
    }

    void disconnect(MinecraftServer server, ServerPlayer player) {
        if (playerId != null && playerId.equals(player.getUUID())) practiceShield.restore(server, player);
    }
}
