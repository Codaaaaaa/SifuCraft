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
    private static final int FPS = Math.clamp(Integer.getInteger("sifucraft.overlayFps", 60), 1, 60);
    private static final class Transfer {
        GpuBuffer buffer;
        int width, height;
        long frame;
        Scope scope;
        int left, top, right, bottom, guiScale;
        volatile int state;
    }
    private final Transfer[] transfers = {new Transfer(), new Transfer(), new Transfer()};
    private OverlayWriter writer;
    private Scope writerScope;
    private boolean enabled = Boolean.getBoolean("sifucraft.overlay");
    private long nextCaptureNs, nextLogNs, frameId, captured, published, skipped, discarded;
    private int requestedWidth, requestedHeight;
    private int viewportWidth, viewportHeight;
    int viewportWidth() { return viewportWidth; }
    int viewportHeight() { return viewportHeight; }
    private long rendered, statsStartNs, statsRendered, statsCaptured, statsPublished;

    boolean enabled() { return enabled; }

    void render(net.minecraft.client.Minecraft minecraft, Scope scope) {
        try {
            reap(scope);
            if (!enabled || scope == null) {
                closeWriter();
                statsStartNs = 0;
                return;
            }
            long now = System.nanoTime();
            if (statsStartNs == 0) {
                statsStartNs = now; nextLogNs = now + 10_000_000_000L;
                statsRendered = rendered; statsCaptured = captured; statsPublished = published;
            }
            rendered++;
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
                            scope.world(), scope.epoch(), true, completed.left, completed.top, completed.right,
                            completed.bottom, completed.guiScale)) published++;
                    else skipped++;
                }
                long shipped = completed.frame;
                for (Transfer transfer : transfers) if (transfer.state == COMPLETE && transfer.frame <= shipped) transfer.state = FREE;
            }
            if (now < nextCaptureNs) return;
            // Advance a stable deadline instead of restarting the clock at each rendered frame.
            // Restarting at 'now' repeatedly rounds a 60 Hz target down when frames arrive late.
            long interval = 1_000_000_000L / FPS;
            nextCaptureNs = nextCaptureNs == 0 || now - nextCaptureNs >= interval
                    ? now + interval : nextCaptureNs + interval;
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
            free.guiScale = minecraft.getWindow().getGuiScale();
            int scale = Math.max(1, free.guiScale);
            int centerX = minecraft.getWindow().getGuiScaledWidth() / 2;
            int centerY = minecraft.getWindow().getGuiScaledHeight() / 2;
            free.left = SifuCraftClient.hasCrosshair() ? Math.max(0, (centerX - 9) * scale) : 0;
            free.top = SifuCraftClient.hasCrosshair() ? Math.max(0, (centerY - 9) * scale) : 0;
            free.right = SifuCraftClient.hasCrosshair() ? Math.min(free.width, (centerX + 10) * scale) : 0;
            free.bottom = SifuCraftClient.hasCrosshair() ? Math.min(free.height, (centerY + 27) * scale) : 0;
            free.frame = ++frameId;
            free.state = PENDING;
            Transfer pending = free;
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, pending.buffer, 0,
                    () -> pending.state = COMPLETE, 0);
            captured++;
            if (now >= nextLogNs) {
                nextLogNs = now + 10_000_000_000L;
                double seconds = (now - statsStartNs) / 1_000_000_000.0;
                LOG.info("[overlay] captured={} published={} skipped={} discarded={} world={} epoch={} render_fps={} capture_fps={} publish_fps={}",
                        captured, published, skipped, discarded, scope.world(), scope.epoch(),
                        Math.round((rendered - statsRendered) / seconds), Math.round((captured - statsCaptured) / seconds),
                        Math.round((published - statsPublished) / seconds));
                statsStartNs = now; statsRendered = rendered; statsCaptured = captured; statsPublished = published;
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

    void updateViewport(net.minecraft.client.Minecraft minecraft) {
        if (!enabled || writer == null) return;
        long viewport = writer.viewport();
        int width = (int) (viewport >>> 32), height = (int) viewport;
        if (width < 320 || height < 240 || width > 16384 || height > 16384) return;
        viewportWidth = width; viewportHeight = height;
        double fit = Math.min(1.0, Math.min((double) OverlayProtocol.MAX_WIDTH / width, (double) OverlayProtocol.MAX_HEIGHT / height));
        width = Math.max(320, (int) Math.floor(width * fit));
        height = Math.max(240, (int) Math.floor(height * fit));
        var window = minecraft.getWindow();
        if (window.getWidth() == width && window.getHeight() == height) return;
        if (requestedWidth == width && requestedHeight == height) return;
        requestedWidth = width; requestedHeight = height;
        window.setWindowed(width, height);
        LOG.info("[overlay] following viewport: {}x{}", width, height);
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
