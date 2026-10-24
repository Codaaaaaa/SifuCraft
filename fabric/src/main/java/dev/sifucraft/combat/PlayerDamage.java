package dev.sifucraft.combat;

import dev.sifucraft.bridge.McBridge;
import dev.sifucraft.bridge.PlayerHit;
import dev.sifucraft.bridge.Protocol;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Incoming bridge hits are consumed once and applied only by the integrated server thread. */
public final class PlayerDamage {
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private record Pending(long session, long id, PlayerHit hit, long receivedNs) {}
    private static final ArrayDeque<Pending> incoming = new ArrayDeque<>(); // bridge thread
    private static final AtomicInteger scheduled = new AtomicInteger();
    private static final AtomicInteger logged = new AtomicInteger();
    public record Applied(long eventId, float before, float after, boolean accepted) {}
    private static java.util.function.Consumer<Applied> diagnostic; // server-thread opt-in check
    public static void diagnostic(java.util.function.Consumer<Applied> listener) { diagnostic = listener; }
    private PlayerDamage() {}

    public static void receive(long session, long id, PlayerHit hit) {
        if (incoming.size() < 128) incoming.addLast(new Pending(session, id, hit, System.nanoTime()));
        else LOG.warn("[player-hits] incoming queue full; discarded event {}", id);
    }

    public static void pump(McBridge bridge, MinecraftServer server, UUID playerId) {
        while (!incoming.isEmpty()) {
            Pending pending = incoming.removeFirst();
            PlayerHit hit = pending.hit;
            var frame = bridge.latest();
            if (!bridge.peerConnected() || frame == null || !frame.minecraftOwnsInput() ||
                    pending.session != bridge.peerSession() || frame.worldGeneration() != hit.worldGeneration() ||
                    frame.anchorEpoch() != hit.anchorEpoch() || server == null || playerId == null ||
                    System.nanoTime() - pending.receivedNs > 2_000_000_000L || scheduled.get() >= 128) {
                if (logged.getAndIncrement() < 200) LOG.info("[player-hits] discarded stale/unowned event={}", pending.id);
                continue;
            }
            scheduled.incrementAndGet();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !player.isAlive() || System.nanoTime() - pending.receivedNs > 2_000_000_000L ||
                            !EnemyMirrors.acceptsHit(pending.session, hit.worldGeneration(), hit.anchorEpoch())) {
                        if (logged.getAndIncrement() < 200) LOG.info("[player-hits] server discarded stale/dead event={}", pending.id);
                        return;
                    }
                    var attacker = EnemyMirrors.hitAttacker(hit.attackerId(), hit.spawn());
                    var source = attacker != null ? player.damageSources().mobAttack(attacker) : player.damageSources().generic();
                    float before = player.getHealth(), absorption = player.getAbsorptionAmount();
                    float amount = hit.sifuDamage() / Protocol.SIFU_HP_PER_MC_HP;
                    boolean accepted = player.hurtServer(player.level(), source, amount);
                    if (diagnostic != null) diagnostic.accept(new Applied(pending.id, before, player.getHealth(), accepted));
                    if (logged.getAndIncrement() < 200) LOG.info("[player-hits] applied event={} attacker={}/{} source={} base={} mc={} accepted={} " +
                                    "HP={}->{} absorption={}->{} flags={} world={} epoch={}", pending.id, hit.attackerId(), hit.spawn(),
                            attacker == null ? "generic" : "mobAttack", hit.sifuDamage(), amount, accepted, before, player.getHealth(),
                            absorption, player.getAbsorptionAmount(), hit.flags(), hit.worldGeneration(), hit.anchorEpoch());
                } finally { scheduled.decrementAndGet(); }
            });
        }
    }
}
