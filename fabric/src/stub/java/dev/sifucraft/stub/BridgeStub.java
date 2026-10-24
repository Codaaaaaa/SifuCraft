package dev.sifucraft.stub;

import dev.sifucraft.bridge.McBridge;
import dev.sifucraft.bridge.Protocol;
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
    private double firstLiveHeapMb = -1;
    private double maxLiveHeapMb;

    public static void main(String[] args) throws InterruptedException {
        long durationSeconds = 0;
        long pingMs = 1000;
        long printMs = 1000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--duration" -> durationSeconds = Long.parseLong(args[++i]);
                case "--ping-ms" -> pingMs = Long.parseLong(args[++i]);
                case "--print-ms" -> printMs = Long.parseLong(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        System.exit(new BridgeStub().run(durationSeconds, pingMs, printMs));
    }

    private static void log(String message) {
        System.out.println(LocalDateTime.now().format(TIME) + " " + message);
    }

    private int run(long durationSeconds, long pingMs, long printMs) throws InterruptedException {
        McBridge bridge = new McBridge(new McBridge.Listener() {
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

        McBridge.Stats s = bridge.stats();
        boolean pass = s.connects >= 1 && s.snapshotsTorn == 0 && s.pingsLost == 0 && s.pongsUnexpected == 0
                && s.eventsDroppedFull == 0 && s.eventsDuplicate == 0 && rttCount > 0 && gridOk;
        log("[result] " + (pass ? "PASS" : "FAIL") + String.format(
                " (connects=%d torn=%d lost=%d unexpected=%d dropped=%d dup=%d pongs=%d grid=%s)", s.connects,
                s.snapshotsTorn, s.pingsLost, s.pongsUnexpected, s.eventsDroppedFull, s.eventsDuplicate, rttCount,
                publishedGrid == 0 ? "none published" : gridOk ? "loaded " + Integer.toUnsignedString(publishedGrid) : "NOT loaded"));
        return pass ? 0 : 1;
    }

    private void observe(McBridge bridge) {
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
