package dev.sifucraft.combat;

import dev.sifucraft.bridge.DamageResult;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.McBridge;
import dev.sifucraft.bridge.Protocol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.Projectile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-resolved damage -> single-producer bridge queue. Published commands are never retried. */
public final class CombatHits {
    public record Scope(long session, int world, int epoch) {}
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private static final ConcurrentLinkedQueue<Hit> outgoing = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger queued = new AtomicInteger();
    private static final ThreadLocal<Batch> attack = new ThreadLocal<>();
    private static final Map<Long, Sent> pending = new HashMap<>(); // bridge thread only
    private static long peerSession, sentCount, resultCount, abandoned, nextStats;
    private static int logged;
    public record Captured(int enemyId, float damage, int flags, float stun) {}
    private static java.util.function.Consumer<Captured> diagnostic; // server-thread development test only
    public static void diagnostic(java.util.function.Consumer<Captured> listener) { diagnostic = listener; }
    private static final class Hit {
        final Scope scope;
        final EnemyFrame.Key key;
        final int entityId;
        final float damage;
        final long createdMs = System.currentTimeMillis();
        int flags;
        Hit(Scope scope, EnemyProxy proxy, float damage, int flags) {
            this.scope = scope; key = proxy.sifuKey(); entityId = proxy.getId(); this.damage = damage; this.flags = flags;
        }
        float stun() { return 0.15f; } // every attack uses Sifu's short Light reaction
    }
    private static final class Batch {
        final Batch parent;
        final ArrayList<Hit> hits = new ArrayList<>();
        int sweepDepth;
        Batch(Batch parent) { this.parent = parent; }
    }
    private record Sent(Hit hit, long atMs) {}
    private CombatHits() {}

    public static boolean canCapture(EnemyProxy proxy) {
        return queued.get() < 256 && EnemyMirrors.damageScope(proxy) != null;
    }

    public static void record(EnemyProxy proxy, DamageSource source, float damage) {
        Scope scope = EnemyMirrors.damageScope(proxy);
        if (scope == null || !Float.isFinite(damage) || damage <= 0) return;
        if (queued.incrementAndGet() > 256) { queued.decrementAndGet(); return; }
        Batch batch = attack.get();
        int flags = source.getDirectEntity() instanceof Projectile ? Protocol.DAMAGE_PROJECTILE : 0;
        if (batch != null && batch.sweepDepth != 0) flags |= Protocol.DAMAGE_SWEEP;
        Hit hit = new Hit(scope, proxy, damage, flags);
        if (batch == null) publish(hit);
        else batch.hits.add(hit);
    }

    public static void beginAttack() { attack.set(new Batch(attack.get())); }
    public static void endAttack() {
        Batch batch = attack.get();
        attack.set(batch.parent);
        for (Hit hit : batch.hits) publish(hit); // after vanilla supplied critical feedback
    }
    private static void publish(Hit hit) {
        outgoing.add(hit);
        if (diagnostic != null) diagnostic.accept(new Captured(hit.key.id(), hit.damage, hit.flags, hit.stun()));
    }
    public static void beginSweep() { if (attack.get() != null) attack.get().sweepDepth++; }
    public static void endSweep() { if (attack.get() != null) attack.get().sweepDepth--; }
    public static void critical(Entity target, boolean critical) {
        Batch batch = attack.get();
        if (!critical || batch == null) return;
        for (Hit hit : batch.hits) if (hit.entityId == target.getId()) hit.flags |= Protocol.DAMAGE_CRITICAL;
    }
    /** Client/bridge thread, after its snapshot and events have been read for this frame. */
    public static void pump(McBridge bridge) {
        long now = System.currentTimeMillis();
        if (!bridge.peerConnected() || peerSession != bridge.peerSession()) {
            abandoned += pending.size();
            pending.clear();
            peerSession = bridge.peerConnected() ? bridge.peerSession() : 0;
        }
        var frame = bridge.latest();
        for (int i = 0; i < 64 && pending.size() < 256; i++) {
            Hit hit = outgoing.peek();
            if (hit == null) break;
            boolean valid = bridge.peerConnected() && frame != null && frame.minecraftOwnsInput() &&
                    hit.scope.session == bridge.peerSession() && hit.scope.world == frame.worldGeneration() &&
                    hit.scope.epoch == frame.anchorEpoch() && now - hit.createdMs <= 2000;
            if (!valid) {
                outgoing.poll(); queued.decrementAndGet(); abandoned++;
                continue;
            }
            long id = bridge.sendEnemyDamage(hit.key.id(), hit.key.spawn(), hit.damage, hit.stun(), hit.flags,
                    hit.scope.world, hit.scope.epoch);
            if (id == 0) break; // ring full: the event has no ID and has not been published yet
            outgoing.poll(); queued.decrementAndGet();
            pending.put(id, new Sent(hit, now));
            sentCount++;
            if (logged++ < 200) LOG.info("[combat] sent damage event={} enemy={} world={} epoch={} mc={} flags={} stun={}",
                    id, hit.key, hit.scope.world, hit.scope.epoch, hit.damage, hit.flags, hit.stun());
        }
        for (Iterator<Map.Entry<Long, Sent>> it = pending.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (now - entry.getValue().atMs > 5000) {
                LOG.warn("[combat] damage event {} has no result after 5 s; not replayed", entry.getKey());
                it.remove(); abandoned++;
            }
        }
        if (now >= nextStats) {
            nextStats = now + 10000;
            LOG.info("[combat] damage sent={} results={} pending={} queued={} abandoned={}", sentCount, resultCount,
                    pending.size(), queued.get(), abandoned);
        }
    }

    public static void result(DamageResult result) {
        Sent sent = pending.remove(result.eventId());
        if (sent == null) return; // a late reply or a duplicate ACK cannot alter proxy life
        Hit hit = sent.hit;
        if (result.enemyId() != hit.key.id() || result.spawn() != hit.key.spawn() || result.worldGeneration() != hit.scope.world ||
                !Float.isFinite(result.sifuDamage()) || !Float.isFinite(result.healthAfter())) {
            LOG.error("[combat] mismatched damage result {}", result);
            return;
        }
        resultCount++;
        if (logged++ < 200) LOG.info("[combat] result event={} enemy={} outcome={} applied={} health_after={}",
                result.eventId(), hit.key, result.result(), result.sifuDamage(), result.healthAfter());
    }
}
