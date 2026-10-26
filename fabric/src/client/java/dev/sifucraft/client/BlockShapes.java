package dev.sifucraft.client;

import dev.sifucraft.blocks.BlockSectionIndex;
import dev.sifucraft.blocks.PlacedBlocks;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.bridge.McBridge;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.ShapeWriter;
import dev.sifucraft.bridge.WorldWriter;
import dev.sifucraft.collision.MapPlacement;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Builds full collision snapshots on the server thread; maps and acknowledgements stay client-side. */
public final class BlockShapes implements AutoCloseable {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("sifucraft.blockShapes","true"));
    record Export(byte[] payload, int sections, long revision, int world, String map, long save) {}
    private final AtomicBoolean building = new AtomicBoolean();
    private volatile Export pending;
    private Export latest;
    private ShapeWriter writer;
    private long mcSession, sifuSession, revision, nextNs, saveKey;
    private volatile long lastIndex = -1, lastTicket = -1;
    private volatile long ticket;
    private int world;
    private String map = "";
    private boolean active;
    private long nextPublishMs;
    private long failures, nextLogNs;

    public boolean active() { return active; }
    public boolean caughtUp() {
        var index = WorldBlocks.index();
        return active && writer != null && latest != null && index != null && !building.get() &&
            lastIndex == index.revision() && lastTicket == ticket && pending == null &&
            writer.appliedRevision(world,map,saveKey,WorldWriter.clockMillis()) >= latest.revision;
    }
    public void update(Minecraft mc, McBridge bridge, MapPlacement placement) {
        var frame = bridge.latest(); var server = mc.getSingleplayerServer();
        if (!ENABLED || !WorldBlocks.ENABLED || !SifuCraftClient.blockWorldAttached() || !bridge.peerConnected() ||
            server == null || WorldBlocks.index() == null) { close(); return; }
        // Re-anchoring and menus can temporarily withhold a presentation camera/placement.
        // They do not discard a static world's negotiated geometry or recompile its chunks.
        if (frame == null || placement == null) return;
        String wantedMap = placement.map();
        if (writer == null || mcSession != bridge.session() || sifuSession != bridge.peerSession()) {
            close(); writer = new ShapeWriter(bridge.session(),bridge.peerSession());
            mcSession = bridge.session(); sifuSession = bridge.peerSession();
        }
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        long save = ShapeProtocol.mapHash(root.toString()); if (save == 0) save = 1;
        if (world != frame.worldGeneration() || !map.equals(wantedMap) || saveKey != save) {
            world = frame.worldGeneration(); map = wantedMap; saveKey = save; ticket++; pending = null; latest = null; active = false;
        }
        // Native retains scoped collision actors across brief ACK delays. Keep the negotiated
        // presentation until scope loss; caughtUp() still requires a fresh applied revision.
        long now = WorldWriter.clockMillis(); active |= writer.ready(world,map,saveKey,now);
        Export out = pending;
        if (out != null && out.world == world && out.map.equals(map) && out.save == saveKey) latest = out;
        if (latest != null && (out == latest || now >= nextPublishMs) &&
            writer.publish(latest.payload,latest.sections,latest.revision,now,world,map,saveKey)) {
            if (pending == latest) pending = null;
            nextPublishMs = now+1000;
        }
        long ns = System.nanoTime();
        if (ns < nextNs || !building.compareAndSet(false,true)) return;
        nextNs = ns + 50_000_000L;
        final int generation = world; final String targetMap = map; final long targetSave = saveKey;
        final long targetTicket = ticket;
        final var index = WorldBlocks.index();
        server.execute(() -> {
            try {
                if (index != WorldBlocks.index()) return;
                var snapshots = index.snapshots();
                boolean moving = snapshots.stream().anyMatch(s -> s.states().values().stream().anyMatch(state -> state.is(Blocks.MOVING_PISTON)));
                long changed = index.revision();
                if (changed == lastIndex && targetTicket == lastTicket && !moving) return;
                Export export = export(snapshots,server.overworld(),placement,++revision,generation,targetMap,targetSave);
                lastIndex = changed; lastTicket = targetTicket;
                if (targetTicket == ticket) pending = export;
            } catch (RuntimeException e) {
                long time = System.nanoTime(); failures++;
                if (time >= nextLogNs) {
                    nextLogNs = time+10_000_000_000L;
                    LoggerFactory.getLogger("sifucraft").error("[block-shapes] complete snapshot deferred failures="+failures,e);
                }
            } finally { building.set(false); }
        });
    }

    static Export export(List<BlockSectionIndex.Snapshot> snapshots, net.minecraft.world.level.BlockGetter level,
                         MapPlacement placement, long revision, int world, String map, long save) {
        var payload = new ByteArrayOutputStream(); int count = 0, total = 0;
        for (var snapshot : snapshots) {
            var section = SectionPos.of(snapshot.section());
            int x = section.minBlockX(), y = section.minBlockY(), z = section.minBlockZ();
            if (x < placement.originX()-MapSlots.SPACING/2 || x >= placement.originX()+MapSlots.SPACING/2) continue;
            var boxes = new ByteArrayOutputStream();
            for (var entry : snapshot.states().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                short local = entry.getKey(); int bx = SectionPos.sectionRelativeX(local), by = SectionPos.sectionRelativeY(local), bz = SectionPos.sectionRelativeZ(local);
                var pos = new BlockPos(x+bx,y+by,z+bz);
                if (pos.getY() < PlacedBlocks.MIN_Y) continue;
                for (var box : entry.getValue().getCollisionShape(level,pos).toAabbs()) {
                    if (++total > ShapeProtocol.MAX_BOXES) throw new IllegalArgumentException("shape capacity exceeded; full state retained for retry");
                    var b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                    b.putFloat((float)((bx+box.minX)*100)).putFloat((float)((bz+box.minZ)*100)).putFloat((float)((by+box.minY)*100));
                    b.putFloat((float)((bx+box.maxX)*100)).putFloat((float)((bz+box.maxZ)*100)).putFloat((float)((by+box.maxY)*100));
                    boxes.writeBytes(b.array());
                }
            }
            if (boxes.size() == 0) continue;
            if (++count > ShapeProtocol.MAX_SECTIONS) throw new IllegalArgumentException("shape section capacity exceeded");
            byte[] data = boxes.toByteArray(); var header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(x-placement.originX()).putInt(z-placement.originZ()).putInt(y-placement.originY()).putInt(data.length/24)
                    .putLong(snapshot.revision()).putLong(ShapeProtocol.hash(data));
            if (payload.size()+header.capacity()+data.length > ShapeProtocol.PAYLOAD_BYTES) throw new IllegalArgumentException("shape payload capacity exceeded");
            payload.writeBytes(header.array()); payload.writeBytes(data);
        }
        return new Export(payload.toByteArray(),count,revision,world,map,save);
    }
    @Override public void close() {
        active = false; if (writer != null) writer.close(); writer = null; pending = null; latest = null;
        mcSession = sifuSession = 0; world = 0; map = ""; ticket++;
    }
}
