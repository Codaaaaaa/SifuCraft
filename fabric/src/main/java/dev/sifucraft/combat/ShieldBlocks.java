package dev.sifucraft.combat;

import dev.sifucraft.bridge.McBridge;
import dev.sifucraft.bridge.PlayerBlocked;
import dev.sifucraft.bridge.PlayerHit;
import dev.sifucraft.bridge.Protocol;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Weapon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Vanilla decides actual item blocking. Only the currently applied bridge hit can emit a reply. */
public final class ShieldBlocks {
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();
    private record Reply(long session, PlayerBlocked payload, long createdNs) { }
    private static final ConcurrentLinkedQueue<Reply> REPLIES = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger QUEUED = new AtomicInteger();
    private static int logged;

    private static final class Attempt {
        final ServerPlayer player;
        final DamageSource source;
        final PlayerHit hit;
        final boolean perfect;
        float blocked;
        Attempt(ServerPlayer player, DamageSource source, PlayerHit hit) {
            this.player = player; this.source = source; this.hit = hit;
            ItemStack use = player.getUseItem();
            perfect = hit.perfectWindow() && player.isUsingItem() && use.has(DataComponents.BLOCKS_ATTACKS) &&
                    !player.getCooldowns().isOnCooldown(use);
        }
    }

    private ShieldBlocks() { }

    record Outcome(boolean accepted, net.minecraft.world.InteractionHand hand, ItemStack item) { }

    static Outcome apply(ServerPlayer player, DamageSource source, float amount, PlayerHit hit, long session, long id) {
        Attempt previous = CURRENT.get();
        Attempt attempt = new Attempt(player, source, hit);
        ItemStack raised = player.getUseItem();
        var raisedHand = player.getUsedItemHand();
        var raisedCopy = raised.copy();
        var blocking = raised.get(DataComponents.BLOCKS_ATTACKS);
        // GuardBreaker must also disable during the raise delay or when the damage source
        // bypasses applyItemBlocking. Capture the covered, raised item before hurt can stop use.
        boolean breakRaised = hit.guardBreaker() &&
                player.isUsingItem() && blocking != null && !player.getCooldowns().isOnCooldown(raised) &&
                covers(player, source, blocking, amount);
        CURRENT.set(attempt);
        boolean accepted;
        try { accepted = player.hurtServer(player.level(), source, amount); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        if (breakRaised && !player.getCooldowns().isOnCooldown(raised)) {
            blocking.disable(player.level(), player, Weapon.AXE_DISABLES_BLOCKING_FOR_SECONDS, raised);
            if (logged++ < 200) LOG.info("[shield] hit={} guard_breaker=true can_defend={} disabled=true", id,
                    (hit.flags() & Protocol.HIT_CAN_DEFEND) != 0);
        }
        if (attempt.blocked > 0 && hit.attackerId() != 0 && session != 0 && id != 0) {
            PlayerBlocked reply = new PlayerBlocked(id, hit.attackerId(), hit.spawn(), hit.worldGeneration(),
                    hit.anchorEpoch(), attempt.blocked, attempt.perfect ? Protocol.BLOCKED_PERFECT : 0);
            if (QUEUED.incrementAndGet() <= 128) REPLIES.add(new Reply(session, reply, System.nanoTime()));
            else { QUEUED.decrementAndGet(); LOG.warn("[shield] feedback queue full; discarded hit={}", id); }
            if (logged++ < 200) LOG.info("[shield] hit={} attacker={}/{} kind={} blocked={} guard_breaker={} cooldown={}",
                    id, hit.attackerId(), hit.spawn(), attempt.perfect ? "perfect" : "ordinary", attempt.blocked,
                    hit.guardBreaker(), player.getCooldowns().isOnCooldown(new ItemStack(net.minecraft.world.item.Items.SHIELD)));
        }
        return new Outcome(accepted, attempt.blocked > 0 ? raisedHand : null, raisedCopy);
    }

    private static boolean covers(ServerPlayer player, DamageSource source,
                                   net.minecraft.world.item.component.BlocksAttacks blocking, float amount) {
        var position = source.getSourcePosition();
        if (position == null) return false;
        var offset = position.subtract(player.position());
        var direction = new net.minecraft.world.phys.Vec3(offset.x, 0, offset.z).normalize();
        double yaw = Math.toRadians(player.getYHeadRot());
        var facing = new net.minecraft.world.phys.Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        double angle = Math.acos(Math.clamp(direction.dot(facing), -1, 1));
        // Use an ordinary source only for the component's directional reduction calculation.
        // Actual damage above retains the original unblockable source and its vanilla rules.
        DamageSource directional = source.getDirectEntity() instanceof EnemyProxy attacker
                ? player.damageSources().mobAttack(attacker) : player.damageSources().generic();
        return blocking.resolveBlockedDamage(directional, amount, angle) > 0;
    }

    /** Allows the verified Sifu timing window to precede vanilla's item raise delay. */
    public static ItemStack perfectItem(LivingEntity entity) {
        Attempt attempt = CURRENT.get();
        return attempt != null && attempt.player == entity && attempt.perfect && entity.isUsingItem() ? entity.getUseItem() : null;
    }

    public static boolean preserveDurability(LivingEntity entity, DamageSource source) {
        Attempt attempt = CURRENT.get();
        return attempt != null && attempt.player == entity && attempt.source == source && attempt.perfect;
    }

    public static void captured(LivingEntity entity, DamageSource source, float blocked) {
        Attempt attempt = CURRENT.get();
        if (attempt != null && attempt.player == entity && attempt.source == source && Float.isFinite(blocked) && blocked > 0)
            attempt.blocked = blocked;
    }

    static float secondsToDisable(EnemyProxy attacker) {
        Attempt attempt = CURRENT.get();
        return attempt != null && attempt.source.getDirectEntity() == attacker && attempt.hit.guardBreaker()
                ? Weapon.AXE_DISABLES_BLOCKING_FOR_SECONDS : 0;
    }

    /** Render/bridge thread: preserve the single producer of the outgoing protocol ring. */
    public static void pump(McBridge bridge) {
        Reply reply;
        while ((reply = REPLIES.peek()) != null) {
            var frame = bridge.latest();
            boolean valid = bridge.peerConnected() && frame != null && frame.minecraftOwnsInput() &&
                    bridge.peerSession() == reply.session && frame.worldGeneration() == reply.payload.worldGeneration() &&
                    frame.anchorEpoch() == reply.payload.anchorEpoch() && System.nanoTime() - reply.createdNs < 2_000_000_000L;
            if (valid && !bridge.sendPlayerBlocked(reply.payload)) return;
            REPLIES.remove(); QUEUED.decrementAndGet();
        }
    }
}
