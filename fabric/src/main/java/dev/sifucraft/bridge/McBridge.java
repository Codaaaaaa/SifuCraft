package dev.sifucraft.bridge;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.sifucraft.bridge.Protocol.Event;
import dev.sifucraft.bridge.Protocol.Header;
import dev.sifucraft.bridge.Protocol.InputPayload;
import dev.sifucraft.bridge.Protocol.McSnap;
import dev.sifucraft.bridge.Protocol.Ring;
import dev.sifucraft.bridge.Protocol.SifuSnap;
import dev.sifucraft.bridge.Protocol.Status;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The Minecraft endpoint of the shared-memory bridge. {@link #tick} and the query methods are
 * single-threaded (the render thread in the mod, the main loop in the stub). The heartbeat runs on
 * its own daemon thread so loading screens that stall the client never look like a crash, and
 * {@link #markClosing()} may be called from any thread.
 */
public final class McBridge implements AutoCloseable {
    /** Receives log lines and protocol events, on the thread that calls {@link #tick}. */
    public interface Listener {
        default void log(String message) {}

        default void onPong(long nonce, long roundTripMicros) {}

        /** SDL scancode, action 1 press / 0 release / -1 repeat, SDL keymod bits. */
        default void onKey(int scancode, int action, int modifiers) {}

        /** SDL mouse button, action 1 press / 0 release. */
        default void onMouseButton(int button, int action, int modifiers) {}

        /** {@link Protocol#OWNER_SIFU} or {@link Protocol#OWNER_MINECRAFT}. */
        default void onOwnerChanged(int owner) {}

        /** The Sifu peer connected, or went away for {@code reason} (e.g. "peer closing"). */
        default void onPeerConnection(boolean connected, String reason) {}
        default void onDamageResult(DamageResult result) {}
        default void onPlayerHit(long sourceSession, long eventId, PlayerHit hit) {}
    }

    /** Counters; read them from the bridge thread. */
    public static final class Stats {
        public long ticks;
        public long connects;
        public long disconnects;
        public long eventsReceived;
        public long eventsSent;
        public long eventsDroppedFull;
        public long eventsStale;
        public long eventsDuplicate;
        public long eventsUnknown;
        public long pingsSent;
        public long pongs;
        public long pongsUnexpected;
        public long pingsLost;      // unanswered while the peer stayed connected
        public long pingsAbandoned; // unanswered because the peer session ended
        public long inputEvents;
        public long snapshotsRead;
        public long snapshotRetries;
        public long snapshotsTorn;
        public long enemyTablesRead;
        public long enemyTablesInvalid;
    }

    private static final int SEQLOCK_ATTEMPTS = 4;
    private static final long PING_LOST_AFTER_MS = 5000;
    private static final long HEARTBEAT_PERIOD_MS = 100;
    private static final VarHandle LONG = JAVA_LONG.varHandle();
    private static final VarHandle INT = JAVA_INT.varHandle();

    private final Listener listener;
    private final Stats stats = new Stats();
    private final Map<Long, Long> pendingPings = new HashMap<>(); // nonce -> sent (ms)

    private SharedMapping mapping;
    private volatile MemorySegment self; // also used by the heartbeat thread and markClosing()
    private MemorySegment peer;
    private MemorySegment sifuSnapshot;
    private MemorySegment mcSnapshot;
    private MemorySegment manifest;
    private MemorySegment blockTable;
    private MemorySegment enemyTable;
    private MemorySegment outgoing;
    private MemorySegment incoming;
    private Thread heartbeatThread;

    private long session;
    private long frame;
    private long nextEventId = 1;
    private long nextNonce = 1;

    private boolean peerConnected;
    private boolean peerIncompatible;
    private int peerPid;
    private long peerSession;
    private long peerHeartbeat;
    private long peerHeartbeatMs; // when the peer heartbeat last advanced (0 = not yet)
    private long peerConnectedMs;
    private long peerLastEventId;
    private long lastTickMs;

    private SifuFrame latest;
    private long lastSifuFrame;
    private CollisionManifest collision = CollisionManifest.NONE;
    private long manifestSeq;
    private int loadedCollision;
    private int blockGeneration;
    private EnemyFrame enemies;
    private long enemySeq;

    public McBridge(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        mapping = SharedMapping.open(Protocol.MAPPING_NAME, Protocol.MAPPING_SIZE);
        MemorySegment base = mapping.segment();
        MemorySegment header = base.asSlice(Protocol.HEADER_OFFSET, Header.SIZE);
        MemorySegment status = base.asSlice(Protocol.MC_STATUS_OFFSET, Status.SIZE);
        peer = base.asSlice(Protocol.SIFU_STATUS_OFFSET, Status.SIZE);
        sifuSnapshot = base.asSlice(Protocol.SIFU_SNAPSHOT_OFFSET, SifuSnap.SIZE);
        mcSnapshot = base.asSlice(Protocol.MC_SNAPSHOT_OFFSET, McSnap.SIZE);
        manifest = base.asSlice(Protocol.COLLISION_MANIFEST_OFFSET, Protocol.Manifest.SIZE);
        blockTable = base.asSlice(Protocol.BLOCK_TABLE_OFFSET, Protocol.Blocks.SIZE);
        enemyTable = base.asSlice(Protocol.ENEMY_TABLE_OFFSET, Protocol.Enemies.SIZE);
        outgoing = base.asSlice(Protocol.MC_TO_SIFU_RING_OFFSET, Ring.SIZE);
        incoming = base.asSlice(Protocol.SIFU_TO_MC_RING_OFFSET, Ring.SIZE);

        header.set(JAVA_INT, Header.VERSION, Protocol.VERSION);
        header.set(JAVA_INT, Header.MAPPING_SIZE, Protocol.MAPPING_SIZE);
        LONG.setRelease(header, (long) Header.LAYOUT_HASH, Protocol.LAYOUT_HASH);
        INT.setRelease(header, (long) Header.MAGIC, Protocol.MAGIC);

        long pid = ProcessHandle.current().pid();
        session = ((unixMicros() << 12) ^ pid) | 1;
        // Drop whatever a previous Minecraft session left unread, and skip events addressed to it.
        LONG.setRelease(outgoing, (long) Ring.WRITE_INDEX, getLongAcquire(outgoing, Ring.READ_INDEX));
        LONG.setRelease(incoming, (long) Ring.READ_INDEX, getLongAcquire(incoming, Ring.WRITE_INDEX));

        status.set(JAVA_INT, Status.VERSION, Protocol.VERSION);
        LONG.setOpaque(status, (long) Status.LAYOUT_HASH, Protocol.LAYOUT_HASH);
        status.set(JAVA_INT, Status.PID, (int) pid);
        LONG.setOpaque(status, (long) Status.SESSION, session);
        LONG.setOpaque(status, (long) Status.HEARTBEAT, 0L);
        INT.setOpaque(status, (long) Status.STATE, Protocol.STATE_RUNNING);
        INT.setRelease(status, (long) Status.MAGIC, Protocol.MAGIC);
        self = status;

        heartbeatThread = Thread.ofPlatform().daemon().name("sifucraft-heartbeat").start(this::heartbeatLoop);
        listener.log(String.format("shared memory %s: %s (%d bytes) protocol v%d layout=%016X session=%016X",
                mapping.created() ? "created" : "opened", Protocol.MAPPING_NAME, Protocol.MAPPING_SIZE,
                Protocol.VERSION, Protocol.LAYOUT_HASH, session));
    }

    /** The heartbeat is the only status field this thread writes. */
    private void heartbeatLoop() {
        long beat = 0;
        while (!Thread.currentThread().isInterrupted()) {
            MemorySegment status = self;
            if (status == null) return;
            LONG.setOpaque(status, (long) Status.UNIX_US, unixMicros());
            LONG.setRelease(status, (long) Status.HEARTBEAT, ++beat);
            try {
                Thread.sleep(HEARTBEAT_PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * One bridge step: peer tracking, events, snapshot exchange. A no-op once closed: Minecraft keeps
     * pumping events (and so calling this) while it shuts down, after the view is unmapped.
     */
    public void tick(long nowMs, McState state) {
        if (mapping == null) return;
        stats.ticks++;
        trackPeer(nowMs);
        pumpEvents();
        expirePings(nowMs);
        if (peerConnected) {
            readSifuSnapshot();
            readManifest();
            readEnemies(nowMs);
        }
        publish(state);
    }

    public void tick(long nowMs) {
        tick(nowMs, McState.NOT_READY);
    }

    /** Sends a ping to Sifu; the answer arrives through {@link Listener#onPong}. */
    public boolean sendPing(long nowMs) {
        if (mapping == null || !peerConnected) return false;
        long nonce = nextNonce++;
        MemorySegment payload = MemorySegment.ofArray(new long[] {nonce, unixMicros()});
        if (!send(Protocol.EVENT_PING, payload)) return false;
        pendingPings.put(nonce, nowMs);
        stats.pingsSent++;
        return true;
    }

    /** Bridge thread only; zero means not published, so this request may be tried later. */
    public long sendEnemyDamage(int enemyId, int spawn, float damage, float stun, int flags, int world, int epoch) {
        if (mapping == null || !peerConnected) return 0;
        byte[] bytes = new byte[Protocol.EnemyDamage.SIZE];
        var b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(enemyId).putInt(spawn).putFloat(damage).putFloat(stun).putInt(flags).putInt(world).putInt(epoch).putInt(0);
        long id = nextEventId;
        return send(Protocol.EVENT_ENEMY_DAMAGE, MemorySegment.ofArray(bytes)) ? id : 0;
    }

    /** Tells Sifu we are going away. Safe to call from a shutdown hook. */
    public void markClosing() {
        MemorySegment status = self;
        if (status != null) INT.setRelease(status, (long) Status.STATE, Protocol.STATE_CLOSING);
    }

    @Override
    public void close() {
        markClosing();
        self = null; // stops the heartbeat and keeps late markClosing() calls off the unmapped view
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            try {
                heartbeatThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mapping != null) mapping.close();
        mapping = null;
        peerConnected = false;
        latest = null;
        enemies = null;
    }

    public boolean peerConnected() {
        return peerConnected;
    }

    public int peerPid() {
        return peerPid;
    }

    public long session() {
        return session;
    }
    public long peerSession() { return peerSession; }

    /** The grid Sifu currently publishes; {@link CollisionManifest#NONE} while none or no Sifu. */
    public CollisionManifest collisionManifest() {
        return collision;
    }

    /** Reports back which manifest generation this side has loaded (0 = none). */
    public void setLoadedCollision(int generation) {
        loadedCollision = generation;
    }

    /**
     * Publishes the blocks placed in {@code map}: {@code count} entries of Unreal block coordinates and
     * palette entry (x, y, z, kind) in {@code xyzKind}, and the palette's generation. Entries beyond
     * {@link Protocol#MAX_BLOCKS} are left out. Returns how many were published.
     */
    public int publishBlocks(String map, int[] xyzKind, int count, int paletteGeneration) {
        if (mapping == null) return 0;
        MemorySegment t = blockTable;
        int n = Math.min(count, Protocol.MAX_BLOCKS);
        // Round down to even: a previous writer may have died mid-update and left the sequence odd.
        long start = t.get(JAVA_LONG, Protocol.Blocks.SEQ) & ~1L;
        LONG.setOpaque(t, (long) Protocol.Blocks.SEQ, start + 1);
        VarHandle.storeStoreFence();
        t.set(JAVA_INT, Protocol.Blocks.GENERATION, ++blockGeneration);
        t.set(JAVA_INT, Protocol.Blocks.COUNT, n);
        t.set(JAVA_INT, Protocol.Blocks.PALETTE_GENERATION, paletteGeneration);
        byte[] name = map.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(name.length, Protocol.Blocks.MAP_SIZE - 1);
        MemorySegment.copy(name, 0, t, JAVA_BYTE, Protocol.Blocks.MAP, length);
        t.set(JAVA_BYTE, Protocol.Blocks.MAP + (long) length, (byte) 0);
        for (int i = 0; i < n; i++) {
            long at = Protocol.Blocks.ENTRIES + (long) i * Protocol.Blocks.ENTRY_SIZE;
            t.set(JAVA_INT, at, xyzKind[4 * i]);
            t.set(JAVA_INT, at + 4, xyzKind[4 * i + 1]);
            t.set(JAVA_INT, at + 8, xyzKind[4 * i + 2]);
            t.set(JAVA_INT, at + 12, xyzKind[4 * i + 3]);
        }
        LONG.setRelease(t, (long) Protocol.Blocks.SEQ, start + 2);
        return n;
    }

    /** Newest consistent Sifu snapshot, or null before the first one (or after a disconnect). */
    public SifuFrame latest() {
        return latest;
    }

    public EnemyFrame enemies() { return enemies; }

    public Stats stats() {
        return stats;
    }

    public int pendingPings() {
        return pendingPings.size();
    }

    private void trackPeer(long nowMs) {
        // If this side itself stalled (e.g. a world loading on the render thread), the peer's
        // heartbeat could not be observed meanwhile: restart its timer instead of judging it.
        boolean stalled = lastTickMs != 0 && nowMs - lastTickMs > Protocol.HEARTBEAT_TIMEOUT_MS / 2;
        lastTickMs = nowMs;
        if (stalled && peerHeartbeatMs != 0) peerHeartbeatMs = nowMs;
        if ((int) INT.getAcquire(peer, (long) Status.MAGIC) != Protocol.MAGIC) {
            if (peerConnected) disconnect("status cleared", nowMs);
            return;
        }
        int version = peer.get(JAVA_INT, Status.VERSION);
        long layout = getLongAcquire(peer, Status.LAYOUT_HASH);
        if (version != Protocol.VERSION || layout != Protocol.LAYOUT_HASH) {
            if (peerConnected) disconnect("incompatible", nowMs);
            if (!peerIncompatible) {
                listener.log(String.format("INCOMPATIBLE peer: protocol v%d layout=%016X (expected v%d layout=%016X)",
                        version, layout, Protocol.VERSION, Protocol.LAYOUT_HASH));
                peerIncompatible = true;
            }
            return;
        }
        peerIncompatible = false;

        long session = getLongAcquire(peer, Status.SESSION);
        long beat = getLongAcquire(peer, Status.HEARTBEAT);
        if (session != peerSession) {
            if (peerConnected) disconnect("peer restarted", nowMs);
            peerSession = session;
            peerPid = peer.get(JAVA_INT, Status.PID);
            peerHeartbeat = beat;
            peerHeartbeatMs = 0; // a new session counts as alive only once its heartbeat moves
            peerLastEventId = 0;
        } else if (beat != peerHeartbeat) {
            peerHeartbeat = beat;
            peerHeartbeatMs = nowMs;
        }

        int state = (int) INT.getAcquire(peer, (long) Status.STATE);
        boolean beating = peerHeartbeatMs != 0 && nowMs - peerHeartbeatMs < Protocol.HEARTBEAT_TIMEOUT_MS;
        if (peerConnected) {
            if (state == Protocol.STATE_CLOSING) {
                disconnect("peer closing", nowMs);
            } else if (!beating) {
                disconnect("heartbeat timeout", nowMs);
            }
        } else if (state == Protocol.STATE_RUNNING && beating) {
            peerConnected = true;
            peerConnectedMs = nowMs;
            stats.connects++;
            listener.log(String.format("peer %s: sifu pid=%d session=%016X (connect #%d)",
                    stats.connects > 1 ? "RECONNECTED" : "CONNECTED", peerPid, peerSession, stats.connects));
            listener.onPeerConnection(true, "connected");
        }
    }

    private void disconnect(String reason, long nowMs) {
        peerConnected = false;
        stats.disconnects++;
        latest = null;
        collision = CollisionManifest.NONE;
        manifestSeq = 0;
        enemies = null;
        enemySeq = 0;
        // Events are addressed to one peer session; answers to them can no longer arrive.
        stats.pingsAbandoned += pendingPings.size();
        pendingPings.clear();
        listener.log(String.format("peer DISCONNECTED (%s): pid=%d session=%016X after %.1f s", reason, peerPid,
                peerSession, (nowMs - peerConnectedMs) / 1000.0));
        listener.onPeerConnection(false, reason);
    }

    private void pumpEvents() {
        long write = getLongAcquire(incoming, Ring.WRITE_INDEX);
        long read = incoming.get(JAVA_LONG, Ring.READ_INDEX);
        if (write < read || write - read > Protocol.RING_CAPACITY) {
            read = write; // the producer re-attached and reset its index
        }
        long lastId = incoming.get(JAVA_LONG, Ring.ACKED_ID);
        while (read < write) {
            VarHandle.loadLoadFence();
            long slot = Ring.SLOTS + (read % Protocol.RING_CAPACITY) * (long) Event.SIZE;
            long id = incoming.get(JAVA_LONG, slot + Event.ID);
            long source = incoming.get(JAVA_LONG, slot + Event.SOURCE_SESSION);
            long target = incoming.get(JAVA_LONG, slot + Event.TARGET_SESSION);
            int type = incoming.get(JAVA_INT, slot + Event.TYPE);
            long payload = slot + Event.PAYLOAD;
            read++;
            stats.eventsReceived++;
            if (source != peerSession || target != session) {
                stats.eventsStale++;
                continue;
            }
            if (id <= peerLastEventId) {
                stats.eventsDuplicate++;
                continue;
            }
            peerLastEventId = id;
            lastId = id;
            switch (type) {
                case Protocol.EVENT_PONG -> {
                    long nonce = incoming.get(JAVA_LONG, payload);
                    long sentMicros = incoming.get(JAVA_LONG, payload + 8);
                    stats.pongs++;
                    if (pendingPings.remove(nonce) == null) {
                        stats.pongsUnexpected++;
                    } else {
                        listener.onPong(nonce, unixMicros() - sentMicros);
                    }
                }
                case Protocol.EVENT_KEY, Protocol.EVENT_MOUSE_BUTTON -> {
                    int code = incoming.get(JAVA_INT, payload + InputPayload.CODE);
                    int action = incoming.get(JAVA_INT, payload + InputPayload.ACTION);
                    int modifiers = incoming.get(JAVA_INT, payload + InputPayload.MODIFIERS);
                    stats.inputEvents++;
                    if (type == Protocol.EVENT_KEY) {
                        listener.onKey(code, action, modifiers);
                    } else {
                        listener.onMouseButton(code, action, modifiers);
                    }
                }
                case Protocol.EVENT_OWNER_CHANGED -> listener.onOwnerChanged(incoming.get(JAVA_INT, payload));
                case Protocol.EVENT_PLAYER_HIT -> {
                    if (incoming.get(JAVA_INT, slot + Event.PAYLOAD_SIZE) != Protocol.PlayerHit.SIZE) {
                        stats.eventsUnknown++;
                        break;
                    }
                    try {
                        byte[] bytes = incoming.asSlice(payload, Protocol.PlayerHit.SIZE).toArray(JAVA_BYTE);
                        listener.onPlayerHit(source, id, PlayerHit.decode(bytes));
                    } catch (IllegalArgumentException invalid) { stats.eventsUnknown++; }
                }
                case Protocol.EVENT_DAMAGE_RESULT -> {
                    if (incoming.get(JAVA_INT, slot + Event.PAYLOAD_SIZE) != Protocol.DamageResult.SIZE) {
                        stats.eventsUnknown++;
                        break;
                    }
                    listener.onDamageResult(new DamageResult(
                            incoming.get(JAVA_LONG, payload + Protocol.DamageResult.EVENT_ID),
                            incoming.get(JAVA_INT, payload + Protocol.DamageResult.ENEMY_ID),
                            incoming.get(JAVA_INT, payload + Protocol.DamageResult.SPAWN),
                            incoming.get(JAVA_INT, payload + Protocol.DamageResult.RESULT),
                            incoming.get(JAVA_FLOAT, payload + Protocol.DamageResult.SIFU_DAMAGE),
                            incoming.get(JAVA_FLOAT, payload + Protocol.DamageResult.HEALTH_AFTER),
                            incoming.get(JAVA_INT, payload + Protocol.DamageResult.WORLD_GENERATION)));
                }
                default -> stats.eventsUnknown++;
            }
        }
        incoming.set(JAVA_LONG, Ring.ACKED_ID, lastId);
        LONG.setRelease(incoming, (long) Ring.READ_INDEX, read);
    }

    private void expirePings(long nowMs) {
        for (Iterator<Long> it = pendingPings.values().iterator(); it.hasNext(); ) {
            if (nowMs - it.next() > PING_LOST_AFTER_MS) {
                it.remove();
                stats.pingsLost++;
            }
        }
    }

    private boolean send(int type, MemorySegment payload) {
        long write = outgoing.get(JAVA_LONG, Ring.WRITE_INDEX);
        long read = getLongAcquire(outgoing, Ring.READ_INDEX);
        if (write - read >= Protocol.RING_CAPACITY || payload.byteSize() > Event.PAYLOAD_CAPACITY) {
            stats.eventsDroppedFull++;
            return false;
        }
        long slot = Ring.SLOTS + (write % Protocol.RING_CAPACITY) * (long) Event.SIZE;
        outgoing.set(JAVA_LONG, slot + Event.ID, nextEventId++);
        outgoing.set(JAVA_LONG, slot + Event.SOURCE_SESSION, session);
        outgoing.set(JAVA_LONG, slot + Event.TARGET_SESSION, peerSession);
        outgoing.set(JAVA_INT, slot + Event.TYPE, type);
        outgoing.set(JAVA_INT, slot + Event.PAYLOAD_SIZE, (int) payload.byteSize());
        MemorySegment slotPayload = outgoing.asSlice(slot + Event.PAYLOAD, Event.PAYLOAD_CAPACITY);
        slotPayload.fill((byte) 0);
        slotPayload.copyFrom(payload);
        LONG.setRelease(outgoing, (long) Ring.WRITE_INDEX, write + 1);
        stats.eventsSent++;
        return true;
    }

    private void readSifuSnapshot() {
        MemorySegment s = sifuSnapshot;
        for (int attempt = 0; attempt < SEQLOCK_ATTEMPTS; attempt++) {
            long before = getLongAcquire(s, SifuSnap.SEQ);
            if ((before & 1) != 0) {
                stats.snapshotRetries++;
                Thread.onSpinWait();
                continue;
            }
            if (before == 0) return; // never written
            SifuFrame copy = new SifuFrame(
                    s.get(JAVA_LONG, SifuSnap.FRAME),
                    s.get(JAVA_LONG, SifuSnap.UNIX_US),
                    s.get(JAVA_INT, SifuSnap.WORLD_GENERATION),
                    s.get(JAVA_INT, SifuSnap.FLAGS),
                    vec3(s, SifuSnap.PLAYER_LOCATION),
                    vec3(s, SifuSnap.PLAYER_ROTATION),
                    vec3(s, SifuSnap.PLAYER_VELOCITY),
                    vec3(s, SifuSnap.CAMERA_LOCATION),
                    vec3(s, SifuSnap.CAMERA_ROTATION),
                    s.get(JAVA_FLOAT, SifuSnap.CAMERA_FOV),
                    s.get(JAVA_FLOAT, SifuSnap.HEALTH),
                    s.get(JAVA_FLOAT, SifuSnap.MAX_HEALTH),
                    utf8z(s, SifuSnap.WORLD_PATH, SifuSnap.WORLD_PATH_SIZE),
                    s.get(JAVA_DOUBLE, SifuSnap.MOUSE_DX),
                    s.get(JAVA_DOUBLE, SifuSnap.MOUSE_DY),
                    s.get(JAVA_DOUBLE, SifuSnap.SCROLL),
                    s.get(JAVA_INT, SifuSnap.INPUT_OWNER),
                    s.get(JAVA_FLOAT, SifuSnap.CAPSULE_HALF_HEIGHT),
                    s.get(JAVA_INT, SifuSnap.ANCHOR_EPOCH));
            VarHandle.loadLoadFence();
            if ((long) LONG.getAcquire(s, (long) SifuSnap.SEQ) != before) {
                stats.snapshotRetries++;
                continue;
            }
            stats.snapshotsRead++;
            latest = copy;
            lastSifuFrame = copy.frame();
            return;
        }
        stats.snapshotsTorn++;
    }

    /** Re-reads the manifest whenever Sifu has written it since the last read. */
    private void readManifest() {
        MemorySegment m = manifest;
        for (int attempt = 0; attempt < SEQLOCK_ATTEMPTS; attempt++) {
            long before = getLongAcquire(m, Protocol.Manifest.SEQ);
            if (before == manifestSeq) return;
            if ((before & 1) != 0) {
                Thread.onSpinWait();
                continue;
            }
            CollisionManifest copy = new CollisionManifest(
                    m.get(JAVA_INT, Protocol.Manifest.GENERATION),
                    m.get(JAVA_INT, Protocol.Manifest.WORLD_GENERATION),
                    m.get(JAVA_INT, Protocol.Manifest.BLOCK_COUNT),
                    m.get(JAVA_LONG, Protocol.Manifest.CELL_COUNT),
                    utf8z(m, Protocol.Manifest.MAP, Protocol.Manifest.MAP_SIZE),
                    utf8z(m, Protocol.Manifest.PATH, Protocol.Manifest.PATH_SIZE));
            VarHandle.loadLoadFence();
            if ((long) LONG.getAcquire(m, (long) Protocol.Manifest.SEQ) != before) continue;
            manifestSeq = before;
            collision = copy;
            return;
        }
    }

    private void readEnemies(long nowMs) {
        for (int attempt = 0; attempt < SEQLOCK_ATTEMPTS; attempt++) {
            long before = getLongAcquire(enemyTable, Protocol.Enemies.SEQ);
            if (before == 0 || before == enemySeq) return;
            if ((before & 1) != 0) continue;
            byte[] bytes = enemyTable.toArray(JAVA_BYTE);
            VarHandle.loadLoadFence();
            if (getLongAcquire(enemyTable, Protocol.Enemies.SEQ) != before) continue;
            enemySeq = before;
            try {
                enemies = EnemyFrame.decode(peerSession, MemorySegment.ofArray(bytes), nowMs);
                stats.enemyTablesRead++;
            } catch (IllegalArgumentException e) {
                enemies = null;
                if (++stats.enemyTablesInvalid <= 5) listener.log("invalid enemy table: " + e.getMessage());
            }
            return;
        }
    }

    private void publish(McState state) {
        MemorySegment s = mcSnapshot;
        // Round down to even: a previous writer may have died mid-update and left the sequence odd.
        long start = s.get(JAVA_LONG, McSnap.SEQ) & ~1L;
        LONG.setOpaque(s, (long) McSnap.SEQ, start + 1);
        VarHandle.storeStoreFence();
        s.set(JAVA_LONG, McSnap.FRAME, ++frame);
        s.set(JAVA_LONG, McSnap.UNIX_US, unixMicros());
        s.set(JAVA_LONG, McSnap.LAST_SIFU_FRAME, lastSifuFrame);
        s.set(JAVA_INT, McSnap.FLAGS, state.flags());
        s.set(JAVA_INT, McSnap.PERSPECTIVE, state.perspective());
        putVec3(s, McSnap.PLAYER_LOCATION, state.playerLocation());
        putVec3(s, McSnap.PLAYER_ROTATION, state.playerRotation());
        putVec3(s, McSnap.PLAYER_VELOCITY, state.playerVelocity());
        putVec3(s, McSnap.CAMERA_LOCATION, state.cameraLocation());
        putVec3(s, McSnap.CAMERA_ROTATION, state.cameraRotation());
        s.set(JAVA_FLOAT, McSnap.CAMERA_FOV, state.cameraFov());
        s.set(JAVA_INT, McSnap.ANCHOR_EPOCH, state.anchorEpoch());
        s.set(JAVA_INT, McSnap.COLLISION_GENERATION, loadedCollision);
        LONG.setRelease(s, (long) McSnap.SEQ, start + 2);
    }

    private static long getLongAcquire(MemorySegment segment, int offset) {
        return (long) LONG.getAcquire(segment, (long) offset);
    }

    private static SifuFrame.Vec3 vec3(MemorySegment s, int offset) {
        return new SifuFrame.Vec3(s.get(JAVA_FLOAT, offset), s.get(JAVA_FLOAT, offset + 4L), s.get(JAVA_FLOAT, offset + 8L));
    }

    private static void putVec3(MemorySegment s, int offset, SifuFrame.Vec3 v) {
        s.set(JAVA_FLOAT, offset, v.x());
        s.set(JAVA_FLOAT, offset + 4L, v.y());
        s.set(JAVA_FLOAT, offset + 8L, v.z());
    }

    private static String utf8z(MemorySegment s, int offset, int capacity) {
        int length = 0;
        while (length < capacity && s.get(JAVA_BYTE, offset + (long) length) != 0) length++;
        return new String(s.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /** Wall clock in microseconds since the Unix epoch; the clock both sides stamp snapshots with. */
    public static long unixMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
    }
}
