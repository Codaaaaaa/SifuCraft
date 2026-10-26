package dev.sifucraft.client;

import dev.sifucraft.blocks.PlacedBlocks;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

/** Controlled actor/nav load in a disposable save. No NPC control or ordinary save edits. */
final class WorldStressCheck {
    private static final int[] COUNTS = {0,32,256,1536,0};
    private static final long[] HOLD_MS = {15000,15000,20000,40000,20000};
    private final Map<BlockPos,BlockState> originals = new LinkedHashMap<>();
    private volatile boolean armed, done;
    private ServerPlayer player;
    private BlockPos origin;
    private int phase = -1, placed;
    private long started, phaseAt;
    private CameraType oldCamera;
    private boolean clientStarted;

    WorldStressCheck() {
        if (!DevWorld.LEVEL_ID.startsWith("sifucraft-world-stress-check-"))
            throw new IllegalStateException("world stress requires an isolated save");
    }
    void tick(Minecraft mc, boolean ready) {
        if (!done && !clientStarted && ready && mc.player != null) {
            if (!SifuCraftClient.worldLayersActive()) throw new AssertionError("stress paired world required");
            oldCamera = mc.options.getCameraType(); mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            clientStarted = true; armed = true;
        }
        if (done) restoreClient(mc);
    }
    private BlockPos position(int i) { return origin.offset(i%32, 0, i/32); }
    private void count(int wanted) {
        var level = player.level();
        while (placed < wanted) {
            var at = position(placed++); originals.putIfAbsent(at, level.getBlockState(at));
            if (!level.setBlock(at, Blocks.STONE.defaultBlockState(), 2))
                throw new AssertionError("stress could not place " + at);
        }
        while (placed > wanted) {
            var at = position(--placed);
            level.setBlock(at, originals.get(at), 2);
        }
        // This must exercise the actual LevelMixin index used by the native actor mirror.
        int actual = PlacedBlocks.between(origin.getX(), origin.getX()+32).stream()
                .filter(block -> originals.containsKey(block.pos())).toList().size();
        if (actual != wanted) throw new AssertionError("stress placed index " + actual + " expected " + wanted);
    }
    void serverTick(MinecraftServer server) {
        if (!armed || done || server.getPlayerList().getPlayers().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (player == null) {
            player = server.getPlayerList().getPlayers().getFirst();
            // A platform ahead and to the right, clear of the player's own capsule. Existing
            // Sifu geometry is untouched; only the disposable MC save/bridge actors change.
            origin = player.blockPosition().offset(6, 0, 6);
            started = now; phaseAt = now; phase = 0;
            LoggerFactory.getLogger("sifucraft").info("[world-stress-test] phase=0 blocks=0 hold_ms={} origin={}", HOLD_MS[0], origin);
        }
        if (now-phaseAt < HOLD_MS[phase]) return;
        if (++phase == COUNTS.length) {
            restore(server); done = true;
            LoggerFactory.getLogger("sifucraft").info("[world-stress-test] PASS: 0/32/256/1536/0 actual indexed blocks; fixture removed; elapsed_ms={}", now-started);
            return;
        }
        count(COUNTS[phase]); phaseAt = System.currentTimeMillis();
        LoggerFactory.getLogger("sifucraft").info("[world-stress-test] phase={} blocks={} hold_ms={}", phase, placed, HOLD_MS[phase]);
    }
    void restore(MinecraftServer server) {
        armed = false;
        if (player != null) {
            for (var entry : originals.entrySet()) player.level().setBlock(entry.getKey(), entry.getValue(), 2);
            placed = 0; originals.clear();
        }
    }
    void restoreClient(Minecraft mc) {
        if (clientStarted) { mc.options.setCameraType(oldCamera); clientStarted = false; }
    }
}
