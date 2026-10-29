package dev.sifucraft.stub;

import dev.sifucraft.bridge.McBridge;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.bridge.DamageResult;
import dev.sifucraft.bridge.SharedMapping;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.CollisionLoader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Stand-in for the Fabric mod while the protocol is validated: attaches as the Minecraft endpoint,
 * prints what Sifu publishes, pings Sifu once per second and reports latency, torn reads, lost
 * events and heap use.
 *
 * <pre>
 *   BridgeStub [--duration SECONDS] [--ping-ms MS] [--print-ms MS]
 * </pre>
 */
public final class BridgeStub {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long TICK_MS = 50;
    private static final long STATS_MS = 10_000;

    private long rttCount;
    private long rttTotalMicros;
    private long rttMaxMicros;
    private long maxSnapshotAgeMicros;
    private long framesObserved;
    private long lastFrameSeen;
    private long frameGaps;
    private long lastEnemyFrame;
    private long enemyFrames;
    private int maxEnemies;
    private final java.util.Map<Long, Integer> expectedDamage = new java.util.HashMap<>();
    private boolean damageTest;
    private int damageStep, damageResults, damageFailures;
    private byte[] firstDamageEvent;
    private double firstLiveHeapMb = -1;
    private double maxLiveHeapMb;

    public static void main(String[] args) throws InterruptedException {
        long durationSeconds = 0;
        long pingMs = 1000;
        long printMs = 1000;
        boolean damageTest = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--duration" -> durationSeconds = Long.parseLong(args[++i]);
                case "--ping-ms" -> pingMs = Long.parseLong(args[++i]);
                case "--print-ms" -> printMs = Long.parseLong(args[++i]);
                case "--damage-test" -> damageTest = true;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        BridgeStub stub = new BridgeStub();
        stub.damageTest = damageTest;
        System.exit(stub.run(durationSeconds, pingMs, printMs));
    }

    private static void log(String message) {
        System.out.println(LocalDateTime.now().format(TIME) + " " + message);
    }

    private int run(long durationSeconds, long pingMs, long printMs) throws InterruptedException {
        McBridge bridge = new McBridge(new McBridge.Listener() {
            @Override public void onDamageResult(DamageResult result) {
                Integer expected = expectedDamage.remove(result.eventId());
                if (expected == null || result.result() != expected || result.enemyId() != 101 || result.worldGeneration() == 0) {
                    damageFailures++;
                }
                if (result.result() == Protocol.DAMAGE_APPLIED && result.sifuDamage() != Protocol.ENEMY_SIFU_HP_PER_MC_HP) damageFailures++;
                damageResults++;
                log("[damage-test] result " + result);
            }
            @Override
            public void log(String message) {
                BridgeStub.log("[bridge] " + message);
            }

            @Override
            public void onPong(long nonce, long roundTripMicros) {
                rttCount++;
                rttTotalMicros += roundTripMicros;
                rttMaxMicros = Math.max(rttMaxMicros, roundTripMicros);
            }
        });
        log(String.format("bridge stub: pid=%d java=%s duration=%s layout=%016X", ProcessHandle.current().pid(),
                Runtime.version(), durationSeconds > 0 ? durationSeconds + " s" : "until Ctrl+C", Protocol.LAYOUT_HASH));
        bridge.start();
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::markClosing));
        CollisionLoader collision = new CollisionLoader(message -> log("[collision] " + message));

        long begin = System.currentTimeMillis();
        long nextPing = begin, nextPrint = begin, nextStats = begin + STATS_MS;
        while (durationSeconds <= 0 || System.currentTimeMillis() - begin < durationSeconds * 1000) {
            Thread.sleep(TICK_MS);
            long now = System.currentTimeMillis();
            collision.update(bridge.collisionManifest());
            bridge.setLoadedCollision(collision.loadedGeneration());
            bridge.tick(now);
            observe(bridge);
            if (damageTest) damageTest(bridge);
            if (now >= nextPing) {
                bridge.sendPing(now);
                nextPing += pingMs;
            }
            if (now >= nextPrint) {
                printFrame(bridge);
                nextPrint += printMs;
            }
            if (now >= nextStats) {
                log("[stats] " + describe(bridge));
                nextStats += STATS_MS;
            }
        }
        // A grid Sifu published must have been loaded (the one it publishes now).
        int publishedGrid = bridge.collisionManifest().generation();
        boolean gridOk = publishedGrid == 0 || collision.loadedGeneration() == publishedGrid;
        collision.close();
        bridge.close();
        log("[final] " + describe(bridge));
        log(String.format("[enemies] frames=%d max_count=%d invalid=%d", enemyFrames, maxEnemies, bridge.stats().enemyTablesInvalid));
        if (damageTest) log(String.format("[damage-test] replies=%d failures=%d pending=%d", damageResults, damageFailures, expectedDamage.size()));

        McBridge.Stats s = bridge.stats();
        boolean pass = s.connects >= 1 && s.snapshotsTorn == 0 && s.pingsLost == 0 && s.pongsUnexpected == 0
                && s.eventsDroppedFull == 0 && s.eventsDuplicate == 0 && s.enemyTablesInvalid == 0 && rttCount > 0 && gridOk
                && (!damageTest || (damageResults == 5 && damageFailures == 0 && expectedDamage.isEmpty()));
        log("[result] " + (pass ? "PASS" : "FAIL") + String.format(
                " (connects=%d torn=%d lost=%d unexpected=%d dropped=%d dup=%d pongs=%d grid=%s)", s.connects,
                s.snapshotsTorn, s.pingsLost, s.pongsUnexpected, s.eventsDroppedFull, s.eventsDuplicate, rttCount,
                publishedGrid == 0 ? "none published" : gridOk ? "loaded " + Integer.toUnsignedString(publishedGrid) : "NOT loaded"));
        return pass ? 0 : 1;
    }

    private void damageTest(McBridge bridge) {
        SifuFrame f = bridge.latest();
        if (f == null || !f.worldPath().startsWith("/SifuCraft/Test/") || damageStep >= 7) return;
        if (damageStep >= 5) {
            if (firstDamageEvent != null) replay(firstDamageEvent);
            damageStep++;
            return;
        }
        int[] outcomes = {Protocol.DAMAGE_APPLIED, Protocol.DAMAGE_STALE, Protocol.DAMAGE_STALE,
                Protocol.DAMAGE_INVALID, Protocol.DAMAGE_NOT_OWNED};
        long id = bridge.sendEnemyDamage(101, damageStep == 1 ? 999 : 1, damageStep == 3 ? Float.NaN : 1, 0.25f, 0,
                f.worldGeneration() + (damageStep == 2 ? 1 : 0), f.anchorEpoch() + (damageStep == 4 ? 1 : 0));
        if (id == 0) return;
        expectedDamage.put(id, outcomes[damageStep]);
        if (damageStep == 0) {
            try (SharedMapping map = SharedMapping.open(Protocol.MAPPING_NAME, Protocol.MAPPING_SIZE)) {
                var ring = map.segment().asSlice(Protocol.MC_TO_SIFU_RING_OFFSET, Protocol.Ring.SIZE);
                long write = ring.get(java.lang.foreign.ValueLayout.JAVA_LONG, Protocol.Ring.WRITE_INDEX);
                firstDamageEvent = ring.asSlice(Protocol.Ring.SLOTS + ((write - 1) % Protocol.RING_CAPACITY) * Protocol.Event.SIZE,
                        Protocol.Event.SIZE).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            }
            replay(firstDamageEvent); // same ID again while the original is in flight
        }
        damageStep++;
    }

    private static void replay(byte[] event) {
        try (SharedMapping map = SharedMapping.open(Protocol.MAPPING_NAME, Protocol.MAPPING_SIZE)) {
            var ring = map.segment().asSlice(Protocol.MC_TO_SIFU_RING_OFFSET, Protocol.Ring.SIZE);
            var longs = java.lang.foreign.ValueLayout.JAVA_LONG.varHandle();
            long write = ring.get(java.lang.foreign.ValueLayout.JAVA_LONG, Protocol.Ring.WRITE_INDEX);
            long read = (long) longs.getAcquire(ring, (long) Protocol.Ring.READ_INDEX);
            if (write - read >= Protocol.RING_CAPACITY) return;
            ring.asSlice(Protocol.Ring.SLOTS + (write % Protocol.RING_CAPACITY) * Protocol.Event.SIZE, Protocol.Event.SIZE)
                    .copyFrom(java.lang.foreign.MemorySegment.ofArray(event));
            longs.setRelease(ring, (long) Protocol.Ring.WRITE_INDEX, write + 1);
        }
    }

    private void observe(McBridge bridge) {
        var enemies = bridge.enemies();
        if (enemies != null && enemies.frame() != lastEnemyFrame) {
            lastEnemyFrame = enemies.frame();
            enemyFrames++;
            maxEnemies = Math.max(maxEnemies, enemies.entries().size());
        }
        SifuFrame frame = bridge.latest();
        if (frame == null || frame.frame() == lastFrameSeen) return;
        if (lastFrameSeen != 0 && frame.frame() > lastFrameSeen + 2) frameGaps++;
        lastFrameSeen = frame.frame();
        framesObserved++;
        long age = McBridge.unixMicros() - frame.unixMicros();
        maxSnapshotAgeMicros = Math.max(maxSnapshotAgeMicros, age);
    }

    /** Heap still in use after a full collection: growth here, unlike raw usage, means a leak. */
    private double liveHeapMb() {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        double live = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        if (firstLiveHeapMb < 0) firstLiveHeapMb = live;
        maxLiveHeapMb = Math.max(maxLiveHeapMb, live);
        return live;
    }

    private static void printFrame(McBridge bridge) {
        SifuFrame f = bridge.latest();
        if (!bridge.peerConnected()) {
            log("[sifu] not connected");
        } else if (f == null) {
            log("[sifu] connected, no snapshot yet");
        } else {
            log(String.format("[sifu] frame=%d gen=%d world=%s pawn=%s loc=%s yaw=%.1f vel=%s cam=%s fov=%.1f hp=%.1f/%.1f age=%.1fms",
                    f.frame(), f.worldGeneration(), f.worldPath(), f.hasPawn(), f.playerLocation(),
                    f.playerRotation().y(), f.playerVelocity(), f.cameraLocation(), f.cameraFov(), f.health(),
                    f.maxHealth(), (McBridge.unixMicros() - f.unixMicros()) / 1000.0));
        }
    }

    private String describe(McBridge bridge) {
        McBridge.Stats s = bridge.stats();
        double liveMb = liveHeapMb();
        return String.format(
                "peer=%s pid=%d connects=%d disconnects=%d frames_seen=%d gaps=%d max_age=%.1fms reads=%d retries=%d torn=%d "
                        + "pings=%d pongs=%d lost=%d abandoned=%d unexpected=%d pending=%d rtt_avg=%.2fms rtt_max=%.2fms "
                        + "sent=%d dropped=%d stale=%d dup=%d unknown=%d live_heap=%.2fMB (first=%.2f max=%.2f)",
                bridge.peerConnected() ? "connected" : "absent", bridge.peerPid(), s.connects, s.disconnects,
                framesObserved, frameGaps, maxSnapshotAgeMicros / 1000.0, s.snapshotsRead, s.snapshotRetries,
                s.snapshotsTorn, s.pingsSent, s.pongs, s.pingsLost, s.pingsAbandoned, s.pongsUnexpected, bridge.pendingPings(),
                rttCount > 0 ? rttTotalMicros / 1000.0 / rttCount : 0.0, rttMaxMicros / 1000.0, s.eventsSent,
                s.eventsDroppedFull, s.eventsStale, s.eventsDuplicate, s.eventsUnknown, liveMb, firstLiveHeapMb,
                maxLiveHeapMb);
    }
}
