package dev.sifucraft.client;

import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** GPU readback and real vanilla pickup, confined to a disposable development save. */
final class WorldEffectsCheck {
    private static WorldEffectsCheck current;
    private static final List<String> PARTICLE_KINDS = Boolean.getBoolean("sifucraft.worldParticleMatrix")
            ? List.of("end_rod", "block", "item", "totem", "firework", "explosion", "smoke",
                    "crit", "enchanted_hit", "damage_indicator", "sweep", "gust", "small_gust", "entity_effect") : List.of("end_rod");
    private int particleCase;
    private int phase, capture;
    private long deadline, at;
    private volatile boolean prepared, spawned, restored;
    private boolean hiddenHud;
    private ServerPlayer player;
    private ItemEntity item;
    private List<ItemStack> inventory;
    private Vec3 base;
    private int baseline;
    private boolean pairedItemDepth, hudClear, worldEmpty, handVisible, layersVerified;

    WorldEffectsCheck() { current = this; }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError("world effects check: " + why); }
    private void teleport(Vec3 position) {
        player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), 0, 0, true);
        player.setDeltaMovement(Vec3.ZERO);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }
    void tick(Minecraft mc, InputInjector input, boolean ready) {
        if (phase == 99 || !ready || mc.player == null || mc.gui.screen() != null) return;
        long now = System.currentTimeMillis();
        if (deadline == 0) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-world-effects-check-"), "isolated world required");
            require(WorldEffects.active(), "worldEffects and overlay flags required");
            deadline = now + 30000 + Math.max(0, PARTICLE_KINDS.size() - 7) * 4000L;
        }
        require(now < deadline, "deadline phase=" + phase + " capture=" + capture);
        var server = mc.getSingleplayerServer();
        switch (phase) {
            case 0 -> {
                hiddenHud = mc.gui.hud.isHidden();
                if (!hiddenHud && !Boolean.getBoolean("sifucraft.worldTestVisibleHud")) mc.gui.hud.toggle();
                var id = mc.player.getUUID();
                server.execute(() -> {
                    player = server.getPlayerList().getPlayer(id); base = player.position();
                    inventory = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                            .mapToObj(i -> player.getInventory().getItem(i).copy()).toList();
                    player.getInventory().clearContent();
                    player.inventoryMenu.broadcastChanges(); teleport(base); prepared = true;
                });
                phase = 1; at = now;
            }
            case 1 -> {
                if (!prepared || now - at < 1000) return;
                mc.player.setYRot(0); mc.player.setXRot(0); capture = 1; phase = 2;
            }
            case 3 -> {
                server.execute(() -> {
                    item = new ItemEntity(player.level(), base.x, base.y + 1.45, base.z + 3,
                            new ItemStack(Items.DIAMOND, 7), 0, 0, 0);
                    item.setNoGravity(true); item.setNeverPickUp();
                    require(player.level().addFreshEntity(item), "spawn vanilla item"); spawned = true;
                }); phase = 4; at = now;
            }
            case 4 -> { if (spawned && now - at > 750) { capture = 2; phase = 5; } }
            case 6 -> {
                server.execute(() -> {
                    item.setNoPickUpDelay(); teleport(new Vec3(base.x, base.y, base.z + 3));
                }); phase = 7; at = now;
            }
            case 7 -> {
                int count = 0;
                for (int i = 0; i < mc.player.getInventory().getContainerSize(); ++i) {
                    var stack = mc.player.getInventory().getItem(i); if (stack.is(Items.DIAMOND)) count += stack.getCount();
                }
                if (count != 7) return;
                require(item.isRemoved(), "vanilla pickup removes the original entity exactly once");
                server.execute(() -> {
                    player.getInventory().clearContent(); player.inventoryMenu.broadcastChanges(); teleport(base);
                }); phase = 8; at = now;
            }
            case 8 -> {
                if (now - at < 750 || !mc.player.getMainHandItem().isEmpty()) return;
                mc.player.setYRot(0); mc.player.setXRot(0);
                // Submit real vanilla particle groups independently of the entity pixels.
                net.minecraft.core.particles.ParticleOptions option = switch (PARTICLE_KINDS.get(particleCase)) {
                    case "block" -> new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    case "item" -> new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, Items.COOKED_BEEF);
                    case "totem" -> ParticleTypes.TOTEM_OF_UNDYING;
                    case "firework" -> ParticleTypes.FIREWORK;
                    case "explosion" -> ParticleTypes.EXPLOSION;
                    case "smoke" -> ParticleTypes.SMOKE;
                    case "crit" -> ParticleTypes.CRIT;
                    case "enchanted_hit" -> ParticleTypes.ENCHANTED_HIT;
                    case "damage_indicator" -> ParticleTypes.DAMAGE_INDICATOR;
                    case "sweep" -> ParticleTypes.SWEEP_ATTACK;
                    case "gust" -> ParticleTypes.GUST;
                    case "small_gust" -> ParticleTypes.SMALL_GUST;
                    case "entity_effect" -> net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFFB030FF);
                    default -> ParticleTypes.END_ROD;
                };
                for (int i = 0; i < 12; ++i) {
                    var particle = mc.particleEngine.createParticle(option,
                            base.x + (i % 4 - 1.5) * .12, base.y + 1.4 + (i / 4) * .12, base.z + 3, 0, 0, 0);
                    require(particle != null, "vanilla particle provider");
                    mc.particleEngine.add(particle);
                }
                capture = 3; phase = 9;
            }
            case 10 -> {
                mc.particleEngine.clearParticles();
                capture = 4; phase = 12;
            }
            case 13 -> {
                if (SifuCraftClient.worldLayersActive() && !layersVerified) {
                    if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
                    server.execute(() -> {
                        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                        player.inventoryMenu.broadcastChanges();
                    });
                    phase = 14; at = now; return;
                }
                if (mc.gui.hud.isHidden() != hiddenHud) mc.gui.hud.toggle();
                server.execute(() -> restore(server)); phase = 11;
            }
            case 14 -> {
                if (now - at > 1000 && mc.player.getMainHandItem().is(Items.DIAMOND_SWORD)) { capture = 5; phase = 15; }
            }
            case 15 -> {
                if (worldEmpty && handVisible && hudClear && pairedItemDepth) {
                    layersVerified = true; phase = 13;
                    LoggerFactory.getLogger("sifucraft").info("[world-layers-test] PASS: paired item color/depth; sword and GUI absent from world while hand remains in HUD; hidden-HUD negative control={}",
                            !Boolean.getBoolean("sifucraft.worldTestVisibleHud"));
                }
            }
            case 11 -> {
                if (!restored) return;
                phase = 99;
                LoggerFactory.getLogger("sifucraft").info("[world-effects-test] PASS: transparent empty world, actual vanilla item pixels, single seven-item pickup, actual particle pixels and cleared-particle negative control; fixture/HUD restored (native occlusion pending)");
            }
            default -> { }
        }
    }
    static void inspectWorld(MemorySegment color, MemorySegment depth, int width, int height, WorldLayerCapture.Snapshot camera) {
        var check = current;
        if (check == null || check.capture == 0) return;
        if (check.capture == 5) {
            int visible = count(color, width, 0, 0, width, height);
            require(visible == 0, "hand/GUI leaked into empty world layer: " + visible);
            check.worldEmpty = true; check.capture = 0;
            evidence(color, width, height, "5-world");
            return;
        }
        if (check.capture == 3) {
            int colored = 0, covered = 0;
            for (int y = height * 3 / 8; y < height * 5 / 8; ++y) for (int x = width * 3 / 8; x < width * 5 / 8; ++x) {
                long at = ((long)y * width + x) * 4;
                if (Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, at + 3)) <= 8) continue;
                colored++;
                float z = depth.get(ValueLayout.JAVA_FLOAT, at);
                if (Float.isFinite(z) && z > 0 && z <= 1) covered++;
            }
            if (colored >= 20) {
                require(covered >= colored * .95, "particle depth coverage for " + PARTICLE_KINDS.get(check.particleCase) + ": " + covered + "/" + colored);
                try {
                    var dir = Path.of("sifucraft-world-effects-check", DevWorld.LEVEL_ID); Files.createDirectories(dir);
                    Files.write(dir.resolve("3-device-depth.f32"), depth.toArray(ValueLayout.JAVA_BYTE));
                    Files.write(dir.resolve("3-" + PARTICLE_KINDS.get(check.particleCase) + "-depth.f32"), depth.toArray(ValueLayout.JAVA_BYTE));
                } catch (java.io.IOException e) { throw new IllegalStateException("write particle depth evidence", e); }
                evidence(color, width, height, "3-" + PARTICLE_KINDS.get(check.particleCase));
                LoggerFactory.getLogger("sifucraft").info("[world-layers-test] particle depth kind={} color={} with_depth={} (coverage measurement, not native occlusion acceptance)",
                        PARTICLE_KINDS.get(check.particleCase), colored, covered);
            }
        }
        if (check.capture == 2) {
            int colored = 0, covered = 0;
            double distance = 0;
            var inverse = new org.joml.Matrix4f(camera.projection()).mul(camera.viewRotation()).invert();
            for (int y = height * 3 / 8; y < height * 5 / 8; ++y) for (int x = width * 3 / 8; x < width * 5 / 8; ++x) {
                long offset = ((long)y * width + x) * 4;
                float z = depth.get(ValueLayout.JAVA_FLOAT, offset);
                require(Float.isFinite(z) && z >= 0 && z <= 1, "invalid paired D32F depth");
                if (Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, offset + 3)) > 8) {
                    colored++;
                    if (z > 0) {
                        covered++;
                        var point = inverse.transform(new org.joml.Vector4f(2f * (x + .5f) / width - 1,
                                2f * (y + .5f) / height - 1, z, 1));
                        distance += camera.z() + point.z / point.w - (check.base.z + 3);
                    }
                }
            }
            if (colored >= 20) {
                require(covered >= colored * .95, "item color/depth disagree: " + covered + "/" + colored);
                require(Math.abs(distance / covered) < .4, "source projection/device-Z mismatch: mean item z error=" + distance / covered);
                check.pairedItemDepth = true;
                LoggerFactory.getLogger("sifucraft").info("[world-layers-test] paired item pixels={} depth_covered={} unprojected_z_error_blocks={}", colored, covered, distance / covered);
            }
        }
        inspect(color, width, height);
    }
    static void inspectHud(MemorySegment color, int width, int height) {
        var check = current;
        if (check == null) return;
        if (check.pairedItemDepth && !check.hudClear && check.phase < 13) {
            if (Boolean.getBoolean("sifucraft.worldTestVisibleHud")) {
                // A visible bare arm or GUI can legitimately reach the center ROI.
                // This run measures the world source with real HUD/resolution settings.
                evidence(color, width, height, "2-visible-hud");
            } else require(count(color, width, width * 3 / 8, height * 3 / 8, width * 5 / 8, height * 5 / 8) == 0,
                    "world item/particles leaked into hidden-HUD layer");
            check.hudClear = true;
        }
        if (check.phase == 15 && !check.handVisible &&
                count(color, width, width * 2 / 3, 0, width, height / 2) > 100) {
            check.handVisible = true;
            evidence(color, width, height, "5-hud");
        }
    }
    private static int count(MemorySegment pixels, int width, int left, int top, int right, int bottom) {
        int result = 0;
        for (int y = top; y < bottom; ++y) for (int x = left; x < right; ++x)
            if (Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, ((long)y * width + x) * 4 + 3)) > 8) result++;
        return result;
    }
    static void evidence(MemorySegment pixels, int width, int height, String name) {
        try {
            var dir = Path.of("sifucraft-world-effects-check", DevWorld.LEVEL_ID); Files.createDirectories(dir);
            var png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
                long p = ((long)y * width + x) * 4;
                int r = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, p));
                int g = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, p + 1));
                int b = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, p + 2));
                int a = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, p + 3));
                png.setRGB(x, height - 1 - y, a << 24 | r << 16 | g << 8 | b);
            }
            ImageIO.write(png, "png", dir.resolve(name + ".png").toFile());
        } catch (java.io.IOException e) { throw new IllegalStateException("write readback evidence", e); }
    }
    static void inspect(MemorySegment pixels, int width, int height) {
        var check = current;
        if (check == null || check.capture == 0) return;
        int visible = 0;
        // The central quarter excludes hands and pickup/advancement toasts.
        for (int y = height * 3 / 8; y < height * 5 / 8; ++y)
            for (int x = width * 3 / 8; x < width * 5 / 8; ++x)
                if (Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, ((long)y * width + x) * 4 + 3)) > 8) visible++;
        int kind = check.capture;
        if (kind == 1) {
            require(visible == 0, "sky/terrain not transparent: visible=" + visible);
            check.baseline = visible;
        } else if (kind == 4 ? visible != check.baseline : visible < check.baseline + 20) return;
        try {
            var dir = Path.of("sifucraft-world-effects-check", DevWorld.LEVEL_ID); Files.createDirectories(dir);
            var png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
                long p = ((long)y * width + x) * 4;
                int r = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, p));
                int g = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, p + 1));
                int b = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, p + 2));
                int a = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, p + 3));
                // OpenGL readback is bottom-up; evidence images use normal top-down rows.
                png.setRGB(x, height - 1 - y, a << 24 | r << 16 | g << 8 | b);
            }
            ImageIO.write(png, "png", dir.resolve(kind + ".png").toFile());
        } catch (java.io.IOException e) { throw new IllegalStateException("write readback evidence", e); }
        LoggerFactory.getLogger("sifucraft").info("[world-effects-test] readback phase={} center_visible={} baseline={} size={}x{}",
                kind, visible, check.baseline, width, height);
        check.capture = 0; check.phase = kind == 1 ? 3 : kind == 2 ? 6 : kind == 3 ? 10 : 13;
        if (kind == 4 && check.particleCase + 1 < PARTICLE_KINDS.size()) {
            ++check.particleCase; check.phase = 8; check.at = System.currentTimeMillis();
        }
    }
    void restore(MinecraftServer server) {
        if (player == null || restored) return;
        if (item != null && !item.isRemoved()) item.discard();
        for (int i = 0; i < inventory.size(); ++i) player.getInventory().setItem(i, inventory.get(i));
        teleport(base); player.inventoryMenu.broadcastChanges(); restored = true;
    }
}
