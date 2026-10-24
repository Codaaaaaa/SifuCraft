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
    private record Pending(long session, long id, PlayerHit hit, long receivedNs,
                           net.minecraft.world.InteractionHand raisedHand, net.minecraft.world.item.ItemStack raisedItem) {}
    private static final ArrayDeque<Pending> incoming = new ArrayDeque<>(); // bridge thread
    private static final AtomicInteger scheduled = new AtomicInteger();
    private static final AtomicInteger logged = new AtomicInteger();
    private record Life(Object player, boolean ready) {}
    private static volatile Life currentLife = new Life(null, false);
    public record Applied(long eventId, float before, float after, boolean accepted) {}
    public record Blocked(long eventId, long session, int world, int epoch, Object clientLife,
                          net.minecraft.world.InteractionHand hand, net.minecraft.world.item.ItemStack item,
                          long receivedNs) { }
    private static volatile java.util.function.Consumer<Blocked> blockListener = block -> { };
    public static void blockListener(java.util.function.Consumer<Blocked> listener) { blockListener = listener; }
    private static java.util.function.Consumer<Applied> diagnostic; // server-thread opt-in check
    public static void diagnostic(java.util.function.Consumer<Applied> listener) { diagnostic = listener; }
    private PlayerDamage() {}

    public static void receive(long session, long id, PlayerHit hit) {
        receive(session, id, hit, null, net.minecraft.world.item.ItemStack.EMPTY);
    }
    public static void receive(long session, long id, PlayerHit hit, net.minecraft.world.InteractionHand raisedHand,
                               net.minecraft.world.item.ItemStack raisedItem) {
        if (incoming.size() < 128) incoming.addLast(new Pending(session, id, hit, System.nanoTime(), raisedHand, raisedItem));
        else LOG.warn("[player-hits] incoming queue full; discarded event {}", id);
    }

    public static void pump(McBridge bridge, MinecraftServer server, UUID playerId, Object lifeToken, boolean ready) {
        currentLife = new Life(lifeToken, ready);
        while (!incoming.isEmpty()) {
            Pending pending = incoming.removeFirst();
            PlayerHit hit = pending.hit;
            var frame = bridge.latest();
            if (!bridge.peerConnected() || frame == null || !frame.minecraftOwnsInput() ||
                    pending.session != bridge.peerSession() || frame.worldGeneration() != hit.worldGeneration() ||
                    frame.anchorEpoch() != hit.anchorEpoch() || !ready || server == null || playerId == null ||
                    System.nanoTime() - pending.receivedNs > 2_000_000_000L || scheduled.get() >= 128) {
                if (logged.getAndIncrement() < 200) LOG.info("[player-hits] discarded stale/unowned event={}", pending.id);
                continue;
            }
            scheduled.incrementAndGet();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(playerId);
                    Life life = currentLife;
                    if (player == null || life.player != lifeToken || !life.ready || !player.isAlive() || System.nanoTime() - pending.receivedNs > 2_000_000_000L ||
                            !EnemyMirrors.acceptsHit(pending.session, hit.worldGeneration(), hit.anchorEpoch())) {
                        if (logged.getAndIncrement() < 200) LOG.info("[player-hits] server discarded stale/dead event={}", pending.id);
                        return;
                    }
                    var attacker = EnemyMirrors.hitAttacker(hit.attackerId(), hit.spawn());
                    var source = SifuDamage.source(player, attacker, (hit.flags() & Protocol.HIT_CAN_DEFEND) != 0);
                    float before = player.getHealth(), absorption = player.getAbsorptionAmount();
                    float amount = hit.sifuDamage() / Protocol.PLAYER_SIFU_HP_PER_MC_HP;
                    // The ordered press has already passed vanilla client item selection. Its
                    // use packet can still be behind this local task on the network event loop.
                    // Cover only this hit; the packet remains responsible for ongoing item use.
                    boolean transientRaise = (hit.perfectWindow() || hit.guardBreaker()) && pending.raisedHand != null && !player.isUsingItem() &&
                            net.minecraft.world.item.ItemStack.isSameItemSameComponents(player.getItemInHand(pending.raisedHand), pending.raisedItem) &&
                            !player.getCooldowns().isOnCooldown(pending.raisedItem);
                    if (transientRaise) player.startUsingItem(pending.raisedHand);
                    ShieldBlocks.Outcome outcome;
                    try { outcome = ShieldBlocks.apply(player, source, amount, hit, pending.session, pending.id); }
                    finally { if (transientRaise) player.stopUsingItem(); }
                    boolean accepted = outcome.accepted();
                    if (outcome.hand() != null) blockListener.accept(new Blocked(pending.id, pending.session,
                            hit.worldGeneration(), hit.anchorEpoch(), lifeToken, outcome.hand(), outcome.item(), pending.receivedNs));
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
