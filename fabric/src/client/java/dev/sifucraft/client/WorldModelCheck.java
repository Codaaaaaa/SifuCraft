package dev.sifucraft.client;

import dev.sifucraft.client.mixin.FireworkRocketAccessor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.LoggerFactory;

/** Real server entities, vanilla renderers and pickup; no explosion or vehicle gameplay claims. */
final class WorldModelCheck {
    private static final int HOLD_MS = Math.clamp(Integer.getInteger("sifucraft.worldModelHoldMs", 500), 500, 3000);
    private static final List<String> CASES = List.of("oak_boat", "oak_chest_boat", "bamboo_raft", "bamboo_chest_raft",
            "tnt", "tnt_flash", "experience_orb", "wind_charge", "firework_rocket", "arrow", "falling_block");
    private static WorldModelCheck current;
    private ServerPlayer player;
    private List<ItemStack> inventory;
    private Vec3 base, center;
    private int xp, level;
    private float progress;
    private volatile Entity entity;
    private volatile boolean prepared, cleared, restored;
    private boolean started, hiddenHud, complete, captured;
    private int sample, phase;
    private long deadline, armAt, phaseAt, tntHash;
    WorldModelCheck() { current = this; }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError("world model: " + why); }
    private void teleport(Vec3 position) {
        player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), 0, 0, true);
        player.setDeltaMovement(Vec3.ZERO);
    }
    private void spawn() {
        String kind = CASES.get(sample);
        var world = player.level();
        Entity created;
        if (kind.equals("experience_orb")) created = new ExperienceOrb(world, center.x, center.y, center.z, 7);
        else if (kind.equals("firework_rocket")) {
            var rocket = new FireworkRocketEntity(world, center.x, center.y, center.z, new ItemStack(Items.FIREWORK_ROCKET));
            ((FireworkRocketAccessor)rocket).sifucraft$setLifetime(100000); created = rocket;
        } else {
            var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(kind.equals("tnt_flash") ? "tnt" : kind));
            require(type != null, "entity registry " + kind);
            created = type.create(world, EntitySpawnReason.COMMAND);
        }
        require(created != null, "create " + kind);
        created.setPos(center); created.setYRot(45); created.setNoGravity(true); created.noPhysics = true;
        created.setDeltaMovement(Vec3.ZERO);
        if (created instanceof net.minecraft.world.entity.item.FallingBlockEntity block) { block.time = 1; block.disableDrop(); }
        if (created instanceof PrimedTnt tnt) tnt.setFuse(kind.equals("tnt_flash") ? 3 : 2000);
        require(world.addFreshEntity(created), "add one server entity " + kind);
        entity = created; prepared = true;
    }
    void serverTick(MinecraftServer server) {
        Entity e = entity;
        if (e == null || e.isRemoved() || phase == 4) return;
        e.setPos(center); e.setDeltaMovement(Vec3.ZERO);
        // A fuse never reaches zero in this disposable renderer fixture.
        if (e instanceof PrimedTnt tnt) tnt.setFuse(CASES.get(sample).equals("tnt_flash") ? 3 : 2000);
    }
    void tick(Minecraft mc, boolean ready) {
        if (complete || !ready || mc.player == null || mc.gui.screen() != null) return;
        long now = System.currentTimeMillis();
        if (!started) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-world-model-check-"), "isolated save required");
            require(SifuCraftClient.worldLayersActive(), "paired world layer required");
            started = true; deadline = now + 55000; hiddenHud = mc.gui.hud.isHidden();
            if (!hiddenHud) mc.gui.hud.toggle();
            var id = mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> {
                player = mc.getSingleplayerServer().getPlayerList().getPlayer(id); base = player.position();
                center = base.add(0, 1.1, 3.5);
                xp = player.totalExperience; level = player.experienceLevel; progress = player.experienceProgress;
                inventory = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                        .mapToObj(i -> player.getInventory().getItem(i).copy()).toList();
                player.getInventory().clearContent(); player.inventoryMenu.broadcastChanges(); prepared = true;
            }); return;
        }
        require(now < deadline, "deadline sample=" + sample + " phase=" + phase);
        mc.particleEngine.clearParticles(); // entity-only samples; particles have a separate matrix
        // XP orbs/projectiles also predict motion on the client. Pin that existing network
        // entity for the isolated still-image sample, then release it for the real XP pickup.
        if (phase == 3 && entity != null) {
            var clientEntity = mc.level.getEntity(entity.getId());
            if (clientEntity != null) {
                clientEntity.setPos(center); clientEntity.setOldPosAndRot(); clientEntity.setDeltaMovement(Vec3.ZERO);
            }
        }
        var server = mc.getSingleplayerServer();
        if (phase == 0 && prepared && mc.player.getMainHandItem().isEmpty()) {
            mc.player.setYRot(0); mc.player.yRotO = 0; mc.player.setXRot(0); mc.player.xRotO = 0;
            phase = 1; armAt = now + 750;
        } else if (phase == 1 && captured) {
            captured = false; prepared = false; cleared = false; phase = 2;
            server.execute(this::spawn);
        } else if (phase == 2 && prepared && entity != null && mc.level.getEntity(entity.getId()) != null) {
            phase = 3; armAt = now + HOLD_MS;
            LoggerFactory.getLogger("sifucraft").info("[world-model-test] show kind={} entity={} hold_ms={}", CASES.get(sample), entity.getId(), HOLD_MS);
        } else if (phase == 3 && captured) {
            captured = false;
            if (CASES.get(sample).equals("experience_orb")) {
                phase = 4; phaseAt = now;
                server.execute(() -> { entity.setNoGravity(false); teleport(center); });
            } else {
                phase = 5;
                server.execute(() -> { entity.discard(); entity = null; cleared = true; });
            }
        } else if (phase == 4 && entity.isRemoved() && player.totalExperience == xp + 7) {
            require(now - phaseAt < 5000, "vanilla orb pickup deadline");
            LoggerFactory.getLogger("sifucraft").info("[world-model-test] vanilla XP pickup exactly +7");
            phase = 5;
            server.execute(() -> { entity = null; teleport(base); cleared = true; });
        } else if (phase == 5 && cleared) {
            phase = 6; armAt = now + 650;
        } else if (phase == 6 && captured) {
            captured = false; sample++;
            if (sample < CASES.size()) { prepared = false; cleared = false; phase = 2; server.execute(this::spawn); }
            else { phase = 7; server.execute(() -> restore(server)); }
        } else if (phase == 7 && restored) {
            if (mc.gui.hud.isHidden() != hiddenHud) mc.gui.hud.toggle();
            complete = true;
            LoggerFactory.getLogger("sifucraft").info("[world-model-test] PASS: {} real server entity cases with visible paired surface depth; TNT white flash differs; removal clears; one +7 XP pickup; no explosion; fixture restored", CASES.size());
        }
    }
    static void inspect(MemorySegment color, MemorySegment depth, int width, int height, WorldLayerCapture.Snapshot camera) {
        var c = current;
        if (c == null || c.complete || c.captured || c.armAt == 0 || System.currentTimeMillis() < c.armAt ||
                c.phase != 1 && c.phase != 3 && c.phase != 6) return;
        int visible = 0, covered = 0, near = 0;
        long hash = 1;
        var inverse = new Matrix4f(camera.projection()).mul(camera.viewRotation()).invert();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            long at = ((long)y * width + x) * 4;
            int a = Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, at + 3));
            if (a <= 8) continue;
            visible++; hash = hash * 31 + color.get(ValueLayout.JAVA_INT, at);
            float z = depth.get(ValueLayout.JAVA_FLOAT, at);
            if (!Float.isFinite(z) || z <= 0 || z > 1) continue;
            covered++;
            var p = inverse.transform(new Vector4f(2f * (x + .5f) / width - 1, 2f * (y + .5f) / height - 1, z, 1));
            if (new Vec3(camera.x() + p.x / p.w, camera.y() + p.y / p.w, camera.z() + p.z / p.w).distanceToSqr(c.center) < 16) near++;
        }
        if (c.phase == 1 || c.phase == 6) require(visible == 0, "entity removal/empty world: " + visible);
        else {
            if (visible < 20) return;
            require(covered >= visible * .95 && near >= covered * .99, CASES.get(c.sample) + " depth/position " + near + "/" + covered + "/" + visible);
            if (CASES.get(c.sample).equals("tnt")) c.tntHash = hash;
            if (CASES.get(c.sample).equals("tnt_flash")) require(hash != c.tntHash, "TNT flash must change visible output");
            WorldEffectsCheck.evidence(color, width, height, "entity-" + CASES.get(c.sample));
            try {
                var dir = Path.of("sifucraft-world-effects-check", DevWorld.LEVEL_ID); Files.createDirectories(dir);
                Files.write(dir.resolve("entity-" + CASES.get(c.sample) + "-depth.f32"), depth.toArray(ValueLayout.JAVA_BYTE));
            } catch (java.io.IOException e) { throw new IllegalStateException("entity depth evidence", e); }
        }
        LoggerFactory.getLogger("sifucraft").info("[world-model-test] kind={} phase={} color={} depth={} near_entity={}",
                c.phase == 1 ? "empty" : CASES.get(c.sample), c.phase, visible, covered, near);
        c.captured = true;
    }
    void restore(MinecraftServer server) {
        if (player == null || inventory == null || restored) return;
        if (entity != null && !entity.isRemoved()) entity.discard(); entity = null;
        for (int i = 0; i < inventory.size(); i++) player.getInventory().setItem(i, inventory.get(i));
        player.totalExperience = xp; player.experienceLevel = level; player.experienceProgress = progress;
        player.connection.send(new ClientboundSetExperiencePacket(progress, xp, level));
        teleport(base); player.inventoryMenu.broadcastChanges(); restored = true;
    }
}
