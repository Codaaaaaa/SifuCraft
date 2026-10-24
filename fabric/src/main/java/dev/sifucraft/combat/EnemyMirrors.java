package dev.sifucraft.combat;

import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.MapPlacement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Latest immutable bridge view -> proxies, all entity changes on the integrated server thread. */
public final class EnemyMirrors {
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private static final long STALE_MS = 1000;
    private static volatile View requested;
    // The remaining fields belong exclusively to the server thread.
    private static final Map<EnemyFrame.Key, EnemyProxy> proxies = new HashMap<>();
    private static long session;
    private static int world;
    private static String map;
    private static long spawned, removed, failures, nextStatsMs;
    private static int loggedDetails;

    private EnemyMirrors() {}
    private record View(EnemyFrame enemies, MapPlacement placement, SifuFrame player) {}

    /** Called on the bridge/client thread. A mismatch immediately removes the old world's targets. */
    public static void offer(SifuFrame frame, EnemyFrame enemies, MapPlacement placement) {
        requested = frame != null && frame.hasPawn() && enemies != null && placement != null &&
                enemies.worldGeneration() != 0 && enemies.worldGeneration() == frame.worldGeneration()
                ? new View(enemies, placement, frame) : null;
    }

    /** Server thread: only a current proxy under MC ownership can produce a damage command. */
    public static CombatHits.Scope damageScope(EnemyProxy proxy) {
        View view = requested;
        if (view == null || !view.player.minecraftOwnsInput() || view.player.anchorEpoch() == 0 ||
                System.currentTimeMillis() - view.enemies.receivedMs() > STALE_MS ||
                session != view.enemies.session() || world != view.enemies.worldGeneration() ||
                proxies.get(proxy.sifuKey()) != proxy) return null;
        return new CombatHits.Scope(session, world, view.player.anchorEpoch());
    }

    public static EnemyProxy testTarget(int id) {
        View view = requested;
        if (view == null || !view.player.worldPath().startsWith("/SifuCraft/Test/")) return null;
        for (var entry : proxies.entrySet()) if (entry.getKey().id() == id) return entry.getValue();
        return null;
    }

    public static void tick(MinecraftServer server) {
        View view = requested;
        long now = System.currentTimeMillis();
        if (view == null || now - view.enemies.receivedMs() > STALE_MS) {
            clear();
            return;
        }
        EnemyFrame enemies = view.enemies;
        if (session != enemies.session() || world != enemies.worldGeneration() || !view.placement.map().equals(map)) {
            clear();
            session = enemies.session();
            world = enemies.worldGeneration();
            map = view.placement.map();
            LOG.info("[combat] proxy world {} map {} session {}", world, map, Long.toUnsignedString(session));
        }
        var wanted = new HashSet<EnemyFrame.Key>();
        for (var enemy : enemies.entries()) if (enemy.alive()) wanted.add(enemy.key());
        proxies.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey()) && !entry.getValue().isRemoved()) return false;
            entry.getValue().discard();
            removed++;
            if (loggedDetails++ < 200) LOG.info("[combat] proxy removed {}", entry.getKey());
            return true;
        });
        for (var enemy : enemies.entries()) {
            if (!enemy.alive()) continue;
            EnemyProxy proxy = proxies.get(enemy.key());
            if (proxy == null) {
                proxy = new EnemyProxy(EnemyEntities.ENEMY_PROXY, server.overworld());
                proxy.mirror(enemy, view.placement);
                if (!server.overworld().addFreshEntity(proxy)) {
                    proxy.discard();
                    if (++failures <= 5) LOG.warn("[combat] proxy spawn failed {}", enemy.key());
                    continue;
                }
                proxies.put(enemy.key(), proxy);
                spawned++;
                if (loggedDetails++ < 200) LOG.info("[combat] proxy added {} width={} height={} max_hp={}",
                        enemy.key(), proxy.getBbWidth(), proxy.getBbHeight(), proxy.getMaxHealth());
            } else {
                proxy.mirror(enemy, view.placement);
            }
        }
        if (now >= nextStatsMs) {
            nextStatsMs = now + 10000;
            LOG.info("[combat] proxies={} world={} spawned={} removed={} failures={}", proxies.size(), world,
                    spawned, removed, failures);
        }
    }

    public static void stop() {
        requested = null;
        clear();
        spawned = removed = failures = nextStatsMs = 0;
        loggedDetails = 0;
    }

    private static void clear() {
        for (var proxy : proxies.values()) { proxy.discard(); removed++; }
        if (!proxies.isEmpty()) LOG.info("[combat] cleared {} proxies", proxies.size());
        proxies.clear();
        session = 0;
        world = 0;
        map = null;
    }
}
