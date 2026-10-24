package dev.sifucraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.sifucraft.bridge.OverlayProtocol;
import dev.sifucraft.bridge.OverlayWriter;
import java.lang.foreign.MemorySegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded asynchronous GPU readback of Minecraft's transparent hand/HUD layer. Opt-in P6 POC. */
final class OverlayCapture implements AutoCloseable {
    record Scope(long mcSession, long sifuSession, int world, int epoch) {}
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private static final int FREE = 0, PENDING = 1, COMPLETE = 2;
    private static final int FPS = Math.clamp(Integer.getInteger("sifucraft.overlayFps", 30), 1, 60);
    private static final class Transfer {
        GpuBuffer buffer;
        int width, height;
        long frame;
        Scope scope;
        volatile int state;
    }
    private final Transfer[] transfers = {new Transfer(), new Transfer(), new Transfer()};
    private OverlayWriter writer;
    private Scope writerScope;
    private boolean enabled = Boolean.getBoolean("sifucraft.overlay");
    private long nextCaptureNs, nextLogNs, frameId, captured, published, skipped, discarded;

    boolean enabled() { return enabled; }

    void render(net.minecraft.client.Minecraft minecraft, Scope scope) {
        try {
            reap(scope);
            if (!enabled || scope == null) {
                closeWriter();
                return;
            }
            if (writer == null || writerScope.mcSession() != scope.mcSession() || writerScope.sifuSession() != scope.sifuSession()) {
                closeWriter();
                writer = new OverlayWriter(scope.mcSession(), scope.sifuSession());
                writerScope = scope;
                LOG.info("[overlay] transport ready: schema={} layout={} fps_limit={}", OverlayProtocol.VERSION,
                        Long.toUnsignedString(OverlayProtocol.LAYOUT_HASH, 16), FPS);
            }
            // Ship only readbacks of the exact session/world/anchor that is still active now.
            Transfer completed = null;
            for (Transfer transfer : transfers) {
                if (transfer.state == COMPLETE && scope.equals(transfer.scope) &&
                        (completed == null || transfer.frame > completed.frame)) completed = transfer;
            }
            if (completed != null) {
                try (var mapped = completed.buffer.map(true, false)) {
                    var pixels = MemorySegment.ofBuffer(mapped.data());
                    if (writer.publish(pixels, completed.width, completed.height, completed.frame,
                            scope.world(), scope.epoch(), true)) published++;
                    else skipped++;
                }
                long shipped = completed.frame;
                for (Transfer transfer : transfers) if (transfer.state == COMPLETE && transfer.frame <= shipped) transfer.state = FREE;
            }
            long now = System.nanoTime();
            if (now < nextCaptureNs) return;
            nextCaptureNs = now + 1_000_000_000L / FPS;
            var target = minecraft.gameRenderer.mainRenderTarget();
            var color = target.getColorTexture();
            if (color == null) return;
            if (color.getFormat() != GpuFormat.RGBA8_UNORM) throw new IllegalStateException("unsupported overlay format " + color.getFormat());
            int bytes = OverlayProtocol.pixelBytes(target.width, target.height);
            Transfer free = null;
            for (Transfer transfer : transfers) if (transfer.state == FREE) { free = transfer; break; }
            if (free == null) { skipped++; return; }
            if (free.buffer == null || free.width != target.width || free.height != target.height) {
                if (free.buffer != null) free.buffer.close();
                free.buffer = RenderSystem.getDevice().createBuffer(() -> "SifuCraft HUD readback",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
                free.width = target.width; free.height = target.height;
                LOG.info("[overlay] readback buffer {}x{} RGBA8", free.width, free.height);
            }
            free.scope = scope;
            free.frame = ++frameId;
            free.state = PENDING;
            Transfer pending = free;
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, pending.buffer, 0,
                    () -> pending.state = COMPLETE, 0);
            captured++;
            if (now >= nextLogNs) {
                nextLogNs = now + 10_000_000_000L;
                LOG.info("[overlay] captured={} published={} skipped={} discarded={} world={} epoch={}",
                        captured, published, skipped, discarded, scope.world(), scope.epoch());
            }
        } catch (RuntimeException | LinkageError e) {
            enabled = false;
            closeWriter();
            LOG.error("[overlay] disabled after capture failure", e);
        }
    }

    private void reap(Scope current) {
        for (Transfer transfer : transfers) if (transfer.state == COMPLETE && (!enabled || !transfer.scope.equals(current))) {
            transfer.state = FREE;
            discarded++;
        }
    }

    private void closeWriter() {
        if (writer != null) writer.close();
        writer = null;
        writerScope = null;
    }

    @Override public void close() {
        enabled = false;
        closeWriter();
        // GPU shutdown owns any outstanding work. Completed/free buffers can be released now.
        for (Transfer transfer : transfers) if (transfer.buffer != null && transfer.state != PENDING) {
            transfer.buffer.close(); transfer.buffer = null;
        }
    }
}
