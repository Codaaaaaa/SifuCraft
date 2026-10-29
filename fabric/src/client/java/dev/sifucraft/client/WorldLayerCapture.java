package dev.sifucraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.sifucraft.bridge.OverlayProtocol;
import dev.sifucraft.bridge.WorldWriter;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.slf4j.LoggerFactory;

/** Paired pre-hand world color/device-depth readbacks, kept separate from the HUD. */
final class WorldLayerCapture implements AutoCloseable {
    private static final int FPS = Math.clamp(Integer.getInteger("sifucraft.worldFps", 30), 1, 60);
    private static final class Transfer {
        GpuBuffer buffer;
        int width, height;
        long frame, capturedNs, capturedMs, sourceFrame;
        OverlayCapture.Scope scope;
        Snapshot camera;
        WorldWriter.Camera wireCamera;
        boolean pending;
        volatile boolean colorDone, depthDone;
        boolean complete() { return pending && colorDone && depthDone; }
    }
    record Snapshot(double x, double y, double z, Matrix4f projection, Matrix4f viewRotation) {}
    private final Transfer[] transfers = {new Transfer(), new Transfer(), new Transfer()};
    private boolean enabled = Boolean.getBoolean("sifucraft.worldLayers");
    private Matrix4f projection;
    private WorldWriter writer;
    private OverlayCapture.Scope writerScope;
    private long nextCaptureNs, frame, captured, completed, published, stale, skipped, nextLogNs;

    boolean enabled() { return enabled; }
    void projection(Matrix4fc matrix) { if (enabled) projection = new Matrix4f(matrix); }

    void updateScope(OverlayCapture.Scope scope) {
        if (writer != null && (scope == null || writerScope.mcSession() != scope.mcSession() || writerScope.sifuSession() != scope.sifuSession())) {
            writer.close(); writer = null; writerScope = null;
        }
    }
    void finish(Minecraft mc, OverlayCapture.Scope scope, CameraRenderState camera, UnrealSpace space, long sourceFrame) {
        if (!enabled || !WorldEffects.active() || scope == null) return;
        try {
            long now = System.nanoTime();
            updateScope(scope);
            if (writer == null) { writer = new WorldWriter(scope.mcSession(), scope.sifuSession()); writerScope = scope; }
            Transfer newest = null;
            for (var t : transfers) {
                if (!t.complete()) continue;
                if (!scope.equals(t.scope) || now - t.capturedNs > 250_000_000L) { t.pending = false; stale++; continue; }
                if (newest == null || t.frame > newest.frame) newest = t;
            }
            if (newest != null) {
                try (var mapped = newest.buffer.map(true, false)) {
                    int plane = OverlayProtocol.pixelBytes(newest.width, newest.height);
                    var data = MemorySegment.ofBuffer(mapped.data());
                    if (Boolean.getBoolean("sifucraft.fluidRenderSelfTest"))
                        FluidRenderCheck.inspect(data.asSlice(0,plane),data.asSlice(plane,plane),newest.width,newest.height,newest.camera);
                    if(Boolean.getBoolean("sifucraft.worldGpuSelfTest"))WorldGpuCheck.inspect(data.asSlice(0,plane),data.asSlice(plane,plane),newest.width,newest.height);
                    if(Boolean.getBoolean("sifucraft.catalogModelSelfTest"))
                        CatalogModelCheck.inspect(data.asSlice(0,plane),data.asSlice(plane,plane),newest.width,newest.height,newest.camera);
                    if (Boolean.getBoolean("sifucraft.blockModelSelfTest"))
                        BlockModelCheck.inspect(data.asSlice(0,plane),data.asSlice(plane,plane),newest.width,newest.height,newest.camera);
                    if (Boolean.getBoolean("sifucraft.worldEffectsSelfTest"))
                        WorldEffectsCheck.inspectWorld(data.asSlice(0, plane), data.asSlice(plane, plane), newest.width, newest.height, newest.camera);
                    if (Boolean.getBoolean("sifucraft.worldCracksSelfTest"))
                        WorldCracksCheck.inspect(data.asSlice(0, plane), data.asSlice(plane, plane), newest.width, newest.height, newest.camera);
                    if (Boolean.getBoolean("sifucraft.worldModelSelfTest"))
                        WorldModelCheck.inspect(data.asSlice(0, plane), data.asSlice(plane, plane), newest.width, newest.height, newest.camera);
                    if (Boolean.getBoolean("sifucraft.avatarSelfTest"))
                        AvatarCheck.inspect(data.asSlice(0, plane), data.asSlice(plane, plane), newest.width, newest.height);
                    if (writer.publish(data, newest.width, newest.height, newest.frame, newest.capturedMs,
                            newest.scope.world(), newest.scope.epoch(), newest.sourceFrame, newest.wireCamera, true)) {published++;
                        if(Boolean.getBoolean("sifucraft.fluidAtmosphereSelfTest"))FluidAtmosphereCheck.published(newest.wireCamera.fog());
                    }
                    else skipped++;
                }
                long shipped = newest.frame;
                for (var t : transfers) if (t.complete() && t.frame <= shipped) t.pending = false;
                completed++;
            }
            var target = mc.gameRenderer.mainRenderTarget();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            if (now >= nextCaptureNs && projection != null && camera != null && camera.initialized) {
                // Same stable deadline as OverlayCapture: restarting at 'now' rounds 60 Hz down to ~47.
                long interval = 1_000_000_000L / FPS;
                nextCaptureNs = nextCaptureNs == 0 || now - nextCaptureNs >= interval
                        ? now + interval : nextCaptureNs + interval;
                if (target.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM ||
                        target.getDepthTexture().getFormat() != GpuFormat.D32_FLOAT)
                    throw new IllegalStateException("world layer requires RGBA8/D32F");
                Transfer free = null;
                for (var t : transfers) if (!t.pending) { free = t; break; }
                if (free == null) skipped++;
                else {
                    int plane = OverlayProtocol.pixelBytes(target.width, target.height);
                    if (free.buffer == null || free.width != target.width || free.height != target.height) {
                        if (free.buffer != null) free.buffer.close();
                        free.buffer = RenderSystem.getDevice().createBuffer(() -> "SifuCraft paired world readback",
                                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, plane * 2);
                        free.width = target.width; free.height = target.height;
                    }
                    free.scope = scope; free.frame = ++frame; free.capturedNs = now;
                    free.capturedMs = WorldWriter.clockMillis(); free.sourceFrame = sourceFrame;
                    free.camera = new Snapshot(camera.pos.x, camera.pos.y, camera.pos.z,
                            new Matrix4f(projection), new Matrix4f(camera.viewRotationMatrix));
                    var origin = space.toUnreal(camera.pos.x, camera.pos.y, camera.pos.z);
                    var fog=WorldWriter.Fog.NONE;
                    int fogKind=camera.fogType==net.minecraft.world.level.material.FogType.WATER?dev.sifucraft.bridge.WorldProtocol.FOG_WATER:
                            camera.fogType==net.minecraft.world.level.material.FogType.LAVA?dev.sifucraft.bridge.WorldProtocol.FOG_LAVA:dev.sifucraft.bridge.WorldProtocol.FOG_NONE;
                    if(fogKind!=dev.sifucraft.bridge.WorldProtocol.FOG_NONE&&camera.fogData!=null){var f=camera.fogData;
                        fog=new WorldWriter.Fog(fogKind,f.color.x,f.color.y,f.color.z,f.color.w,f.environmentalStart,f.environmentalEnd,f.renderDistanceStart,f.renderDistanceEnd);
                    }
                    free.wireCamera = new WorldWriter.Camera(origin.x(), origin.y(), origin.z(),
                            projection.get(new float[16]), camera.viewRotationMatrix.get(new float[16]),fog);
                    free.colorDone = free.depthDone = false; free.pending = true;
                    Transfer pending = free;
                    encoder.copyTextureToBuffer(target.getColorTexture(), free.buffer, 0, () -> pending.colorDone = true, 0);
                    encoder.copyTextureToBuffer(target.getDepthTexture(), free.buffer, plane, () -> pending.depthDone = true, 0);
                    captured++;
                }
            }
            // The world commands and both copies precede this clear in the same GPU queue.
            // Vanilla render3dHud then draws hands and GUI onto a transparent, independent layer.
            encoder.clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0, 0, 0, 0), target.getDepthTexture(), 0);
            if (now >= nextLogNs) {
                nextLogNs = now + 10_000_000_000L;
                LoggerFactory.getLogger("sifucraft").info("[world-layers] paired={} completed={} published={} stale={} skipped={} world={} epoch={}",
                        captured, completed, published, stale, skipped, scope.world(), scope.epoch());
            }
        } catch (RuntimeException | LinkageError e) {
            enabled = false;
            if (writer != null) { writer.close(); writer = null; writerScope = null; }
            LoggerFactory.getLogger("sifucraft").error("[world-layers] disabled after paired capture failure", e);
        }
    }

    @Override public void close() {
        enabled = false;
        if (writer != null) { writer.close(); writer = null; }
        for (var t : transfers) if (t.buffer != null && (!t.pending || t.complete())) { t.buffer.close(); t.buffer = null; }
    }
}
