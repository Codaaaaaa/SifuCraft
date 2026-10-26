package dev.sifucraft.client;

import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.LoggerFactory;

/** GPU and real left-button mining checks, restricted to a disposable save. */
final class WorldCracksCheck {
    private static final int BREAKER = 1_000_000_001;
    private static final int[] STAGES = {-1, 0, 5, 9, -1};
    private static final int HOLD_MS = Math.clamp(Integer.getInteger("sifucraft.worldCracksHoldMs", 400), 400, 3000);
    private static WorldCracksCheck current;
    private ServerPlayer player;
    private List<ItemStack> inventory;
    private BlockPos target;
    private BlockState original;
    private float testYaw;
    private volatile boolean prepared, restored;
    private boolean started, complete, captured, hiddenHud;
    private int sample, firstPixels, lastPixels, liveMin = 10, liveMax = -1, liveFrames;
    private long deadline, armAt, clickAt;
    WorldCracksCheck() { current = this; }
    private static void require(boolean ok, String text) { if (!ok) throw new AssertionError("world cracks: " + text); }

    void tick(Minecraft mc, InputInjector input, boolean ready) {
        if (complete || !ready || mc.player == null || mc.gui.screen() != null) return;
        long now = System.currentTimeMillis();
        if (!started) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-world-cracks-check-"), "isolated save required");
            require(SifuCraftClient.worldLayersActive(), "paired world layer required");
            started = true; deadline = now + 25000;
            hiddenHud = mc.gui.hud.isHidden();
            if (!hiddenHud) mc.gui.hud.toggle(); // exclude the independently rendered selection outline
            // The imported fixture includes native walls. Put the mining target on a clear ray.
            var eye = mc.player.getEyePosition();
            var nativeScene = dev.sifucraft.collision.SifuCollision.active();
            for (int yaw : new int[]{0, 90, 180, -90}) {
                var direction = net.minecraft.world.phys.Vec3.directionFromRotation(0, yaw);
                var candidate = BlockPos.containing(mc.player.position().add(direction.scale(2)).add(0, 1, 0));
                var center = new net.minecraft.world.phys.Vec3(candidate.getX() + .5, eye.y, candidate.getZ() + .5);
                if (nativeScene != null && nativeScene.clip(eye, center) != null) continue;
                target = candidate;
                testYaw = (float)Math.toDegrees(Math.atan2(eye.x - center.x, center.z - eye.z)); break;
            }
            require(target != null, "fixture needs one unobstructed mining direction");
            var id = mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> {
                player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                require(player.gameMode.isSurvival(), "survival mining required");
                inventory = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                        .mapToObj(i -> player.getInventory().getItem(i).copy()).toList();
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.inventoryMenu.broadcastChanges();
                original = player.level().getBlockState(target);
                player.level().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState()); prepared = true;
            });
            return;
        }
        require(now < deadline, "deadline sample=" + sample);
        if (!prepared || sample < 7 && (!mc.player.getMainHandItem().isEmpty() || !mc.level.getBlockState(target).is(Blocks.STONE))) return;
        // Footstep/landing and real mining particles have independent lifetimes. Clear those
        // only for this fixture's decal-only samples and its cancellation negative control.
        if (sample < 5 || sample == 6) mc.particleEngine.clearParticles();
        if (armAt == 0) {
            mc.player.setYRot(testYaw); mc.player.yRotO = testYaw;
            mc.player.setXRot(0); mc.player.xRotO = 0;
            mc.level.destroyBlockProgress(BREAKER, target, -1); armAt = now + 750;
        }
        if (sample < STAGES.length && captured) {
            captured = false; sample++;
            if (sample < STAGES.length) {
                mc.level.destroyBlockProgress(BREAKER, target, STAGES[sample]); armAt = now + HOLD_MS;
            } else {
                require(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getBlockPos().equals(target),
                        "actual left-button target=" + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit ? hit.getBlockPos() + " at " + hit.getLocation() : mc.hitResult)
                                + " expected=" + target + " player=" + mc.player.position() + " camera=" + mc.gameRenderer.mainCamera().position()
                                + " yaw=" + mc.player.getYRot() + " pitch=" + mc.player.getXRot());
                input.mouseButton(1, 1, 0); clickAt = now; armAt = now + 200;
            }
        }
        if (sample == 5 && now - clickAt >= 1300) {
            input.mouseButton(1, 0, 0);
            require(liveFrames >= 3 && liveMin >= 0 && liveMax > liveMin,
                    "actual held left button advances vanilla damage and visible frames: " + liveMin + ".." + liveMax + " frames=" + liveFrames);
            sample = 6; armAt = now + 500; captured = false;
        }
        if (sample == 6 && captured) {
            mc.getSingleplayerServer().execute(() -> restore(mc.getSingleplayerServer())); sample = 7;
        }
        if (sample == 7 && restored) {
            if (mc.gui.hud.isHidden() != hiddenHud) mc.gui.hud.toggle();
            complete = true;
            LoggerFactory.getLogger("sifucraft").info("[world-cracks-test] PASS: empty base, vanilla stages 0/5/9 with paired surface depth, cancel clears; actual survival left-button progress {}..{} with {} visible frames; fixture restored", liveMin, liveMax, liveFrames);
        }
    }
    static void observe(net.minecraft.client.renderer.state.level.LevelRenderState state) {
        var c = current;
        if (c == null || c.sample != 5) return;
        for (var breaking : state.blockBreakingRenderStates) if (breaking.blockPos().equals(c.target)) {
            c.liveMin = Math.min(c.liveMin, breaking.progress()); c.liveMax = Math.max(c.liveMax, breaking.progress());
        }
    }
    static void inspect(MemorySegment color, MemorySegment depth, int width, int height, WorldLayerCapture.Snapshot camera) {
        var c = current;
        if (c == null || !c.prepared || c.complete || c.captured || c.armAt == 0 || System.currentTimeMillis() < c.armAt || c.sample > 6) return;
        int visible = 0, covered = 0, onFace = 0;
        var inverse = new Matrix4f(camera.projection()).mul(camera.viewRotation()).invert();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            long at = ((long)y * width + x) * 4;
            if (Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, at + 3)) <= 8) continue;
            visible++;
            float z = depth.get(ValueLayout.JAVA_FLOAT, at);
            if (!Float.isFinite(z) || z <= 0 || z > 1) continue;
            covered++;
            var p = inverse.transform(new Vector4f(2f * (x + .5f) / width - 1, 2f * (y + .5f) / height - 1, z, 1));
            double px = camera.x() + p.x / p.w - c.target.getX();
            double py = camera.y() + p.y / p.w - c.target.getY();
            double pz = camera.z() + p.z / p.w - c.target.getZ();
            if (px >= -.05 && px <= 1.05 && py >= -.05 && py <= 1.05 && pz >= -.05 && pz <= 1.05 &&
                    Math.min(Math.min(Math.abs(px), Math.abs(px - 1)), Math.min(Math.min(Math.abs(py), Math.abs(py - 1)), Math.min(Math.abs(pz), Math.abs(pz - 1)))) < .05) onFace++;
        }
        boolean empty = c.sample == 0 || c.sample == 4 || c.sample == 6;
        if (empty) { require(visible == 0, "cancel/empty world must stay transparent: " + visible); }
        else {
            if (visible < 20) return;
            require(covered == visible && (c.sample == 5 ? onFace >= 20 : onFace >= visible * .99), "cracks require target-face depth: " + onFace + "/" + covered + "/" + visible);
            if (c.sample == 5) { c.liveFrames++; return; }
            if (c.sample == 1) c.firstPixels = visible;
            if (c.sample == 3) { c.lastPixels = visible; require(c.lastPixels > c.firstPixels, "late cracks cover more of the face"); }
        }
        try {
            var dir = Path.of("sifucraft-world-cracks-check", DevWorld.LEVEL_ID); Files.createDirectories(dir);
            var png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                long at = ((long)y * width + x) * 4;
                int r = Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, at)), g = Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, at + 1));
                int b = Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, at + 2)), a = Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, at + 3));
                png.setRGB(x, height - 1 - y, a << 24 | r << 16 | g << 8 | b);
            }
            ImageIO.write(png, "png", dir.resolve(c.sample + "-cracks.png").toFile());
        } catch (java.io.IOException e) { throw new IllegalStateException("crack evidence", e); }
        LoggerFactory.getLogger("sifucraft").info("[world-cracks-test] sample={} visible={} paired_depth={} on_target_face={}", c.sample, visible, covered, onFace);
        c.captured = true;
    }
    void restore(MinecraftServer server) {
        if (player == null || original == null || restored) return;
        player.level().setBlockAndUpdate(target, original);
        for (int i = 0; i < inventory.size(); i++) player.getInventory().setItem(i, inventory.get(i));
        player.inventoryMenu.broadcastChanges(); restored = true;
    }
}
