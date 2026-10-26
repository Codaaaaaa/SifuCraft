package dev.sifucraft.client;

import dev.sifucraft.blocks.PlacedBlocks;
import dev.sifucraft.collision.CollisionGrid;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.storage.AtomicFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** No Minecraft client, world, server or native game is created by these offline checks. */
public final class P7OfflineTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void atomic(Path directory) throws IOException {
        Path file = directory.resolve("atomic.txt");
        AtomicFiles.write(file, out -> out.write("旧内容\n"));
        try {
            AtomicFiles.write(file, out -> { out.write("incomplete"); throw new IOException("interrupted writer fixture"); });
            throw new AssertionError("interrupted write succeeded");
        } catch (IOException expected) { check(Files.readString(file).equals("旧内容\n"), "failed save preserves complete old index"); }
        AtomicFiles.write(file, out -> out.write("新内容\nsecond line\n"));
        check(Files.readString(file).equals("新内容\nsecond line\n"), "complete UTF-8 save replaces index");
        try (var files = Files.list(directory)) { check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "temporary files removed"); }
    }

    private static CollisionGrid grid(Path file, int x, int y, int z) throws IOException {
        byte[] name = "camera-fixture".getBytes(StandardCharsets.UTF_8);
        var bytes = ByteBuffer.allocate(40 + name.length + 76).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{'S','C','V','X'}).putInt(1).putFloat(12.5f).putInt(8);
        bytes.putFloat(0).putFloat(0).putFloat(0).putInt(name.length).put(name).putInt(1);
        bytes.putInt(x).putInt(y).putInt(z);
        for (int i = 0; i < 8; i++) bytes.putLong(-1L);
        Files.write(file, java.util.Arrays.copyOf(bytes.array(), bytes.position()));
        return CollisionGrid.read(file);
    }

    private static void camera(Path directory) throws IOException {
        var placement = new MapPlacement("camera-fixture", 0, 64, 0);
        var wall = grid(directory.resolve("wall.scvx"), 0, 2, 1);
        var scene = new SifuCollision.Active(wall, placement);
        var from = new Vec3(.5, 65.5, .5); var to = new Vec3(.5, 65.5, 4.5);
        var miss = BlockHitResult.miss(to, Direction.NORTH, BlockPos.containing(to));
        var hit = CameraCollision.nearest(scene, from, to, miss);
        check(Math.abs(hit.getLocation().z - 2) < 1e-6, "third-person ray stops at Sifu wall");
        var nearer = new BlockHitResult(new Vec3(.5, 65.5, 1), Direction.NORTH, new BlockPos(0,65,1), false);
        check(CameraCollision.nearest(scene, from, to, nearer) == nearer, "closer vanilla block wins");
        var farther = new BlockHitResult(new Vec3(.5, 65.5, 4), Direction.NORTH, new BlockPos(0,65,4), false);
        check(CameraCollision.nearest(scene, from, to, farther) != farther, "closer Sifu wall wins");
        check(CameraCollision.nearest(null, from, to, miss) == miss, "no imported scope preserves vanilla");
        var away = new Vec3(.5, 65.5, -3);
        check(CameraCollision.nearest(scene, from, away, miss) == miss, "front/rear rays do not hit a wall in the opposite direction");
        var empty = grid(directory.resolve("far.scvx"), 20, 20, 20);
        var door = new SifuCollision.Active(empty, placement, wall);
        check(Math.abs(CameraCollision.nearest(door, from, to, miss).getLocation().z - 2) < 1e-6,
                "dynamic door geometry also stops the camera");
        // Match the eight 0.1-block corner rays used by the installed Camera.getMaxZoom.
        for (int i = 0; i < 8; i++) {
            var origin = from.add((i & 1) == 0 ? -.1 : .1, (i & 2) == 0 ? -.1 : .1, (i & 4) == 0 ? -.1 : .1);
            check(CameraCollision.nearest(scene, origin, origin.add(0,0,4), miss) != miss, "camera corner ray " + i);
        }
    }

    private static void persistence(Path directory) throws IOException {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap(); // registry initialization only; no client/server or save is opened
        Path saveA = Files.createDirectory(directory.resolve("save-a")), saveB = Files.createDirectory(directory.resolve("save-b"));
        var slots = new MapSlots(message -> {}); slots.open(saveA);
        var first = slots.placement("关卡A"); var second = slots.placement("关卡B"); slots.close();
        slots.open(saveA);
        check(slots.placement("关卡B").equals(second) && slots.placement("关卡A").equals(first), "map origins survive reverse-order reopen");
        Path maps = saveA.resolve("sifucraft-maps.properties"), oldMaps = saveA.resolve("maps.saved");
        Files.move(maps, oldMaps); Files.createDirectory(maps);
        var third = slots.placement("关卡C");
        Files.delete(maps); Files.move(oldMaps, maps); slots.close(); slots.open(saveA);
        check(slots.placement("关卡C").equals(third) && third.originX() == MapSlots.SPACING*2 && slots.placement("关卡A").equals(first),
                "failed map-index replacement retries without changing earlier origins");
        slots.close(); slots.open(saveB);
        check(slots.placement("关卡B").originX() == 0, "separate save has independent map slots"); slots.close();
        PlacedBlocks.open(saveA, message -> {});
        PlacedBlocks.changed(new BlockPos(1,70,3), Blocks.STONE.defaultBlockState(), true);
        PlacedBlocks.changed(new BlockPos(MapSlots.SPACING + 1,70,3), Blocks.OAK_PLANKS.defaultBlockState(), true);
        PlacedBlocks.close(); PlacedBlocks.open(saveA, message -> {});
        check(PlacedBlocks.between(0,MapSlots.SPACING).size() == 1 && PlacedBlocks.between(MapSlots.SPACING,MapSlots.SPACING*2).size() == 1,
                "two maps retain independent block coordinates after reopen");
        PlacedBlocks.changed(new BlockPos(1,70,3), Blocks.AIR.defaultBlockState(), false);
        PlacedBlocks.close(); PlacedBlocks.open(saveA, message -> {});
        check(PlacedBlocks.between(0,MapSlots.SPACING).isEmpty() && PlacedBlocks.between(MapSlots.SPACING,MapSlots.SPACING*2).size() == 1,
                "deleting a block persists only in its own map");
        Path index = saveA.resolve("sifucraft-blocks.txt"), backup = saveA.resolve("blocks.saved");
        Files.move(index, backup); Files.createDirectory(index); // force replacement failure on this fixture's path
        PlacedBlocks.changed(new BlockPos(2,70,3), Blocks.STONE.defaultBlockState(), true);
        PlacedBlocks.saveIfDirty();
        Files.delete(index); Files.move(backup, index);
        PlacedBlocks.close(); PlacedBlocks.open(saveA, message -> {});
        check(PlacedBlocks.between(0,MapSlots.SPACING).size() == 1, "failed block save remains dirty and shutdown retries it");
        for (int i = 0; i < 1536; i++) PlacedBlocks.changed(new BlockPos(100 + i%32,80,i/32), Blocks.STONE.defaultBlockState(), true);
        PlacedBlocks.close(); PlacedBlocks.open(saveA, message -> {});
        check(PlacedBlocks.between(0,MapSlots.SPACING).size() == 1537, "current 1536-block capacity survives full save/reopen");
        PlacedBlocks.close(); PlacedBlocks.open(saveB, message -> {});
        check(PlacedBlocks.between(0,MapSlots.SPACING*2).isEmpty(), "another save cannot inherit the previous block index");
        PlacedBlocks.close();
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("sifucraft-p7-offline-");
        try { atomic(directory); camera(directory); persistence(directory); }
        finally {
            // Only files created by this fixture, under its own temporary directory.
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("P7 offline camera, atomic-write and multi-map/save persistence checks PASS");
    }
}
