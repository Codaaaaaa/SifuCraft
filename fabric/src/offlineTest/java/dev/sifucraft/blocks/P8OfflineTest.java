package dev.sifucraft.blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Installed vanilla state/shape and revision checks; no client, server or user save. */
public final class P8OfflineTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void invalid(String key) {
        try { BlockStateKey.decode(key); throw new AssertionError("accepted " + key); }
        catch (IllegalArgumentException expected) { /* fail closed */ }
    }

    private static void revisions() {
        var index = new BlockSectionIndex(); var at = new BlockPos(-1, 70, -17);
        long section = SectionPos.of(at).asLong(); short local = SectionPos.sectionRelativePos(at);
        index.changed(at, Blocks.STONE_BUTTON.defaultBlockState());
        var first = index.snapshot(section);
        check(first.states().get(local).is(Blocks.STONE_BUTTON), "non-solid button survives tracking");
        index.changed(at, Blocks.STONE_BUTTON.defaultBlockState());
        check(index.revision() == first.revision(), "duplicate state does not dirty a revision");
        index.changed(at, Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.POWERED, true));
        index.acknowledge(section, first.revision());
        check(index.dirtySnapshots(1).size() == 1, "late ACK cannot discard button release/press");
        check(!first.states().get(local).getValue(BlockStateProperties.POWERED), "published snapshots are immutable");
        index.changed(at, Blocks.AIR.defaultBlockState());
        var removed = index.snapshot(section);
        check(removed.states().isEmpty(), "air creates explicit section deletion");
        index.acknowledge(section, removed.revision());
        check(index.dirtySnapshots(10).isEmpty(), "acknowledged deletion converges");
        index.replace(section, Map.of(local, Blocks.MOVING_PISTON.defaultBlockState()));
        check(index.snapshot(section).states().get(local).is(Blocks.MOVING_PISTON), "moving piston is not air");
        index.unloadChunk(-1, -2);
        check(index.snapshot(section).states().isEmpty(), "unload publishes a tombstone");
        index.replace(section, Map.of(local, Blocks.CHEST.defaultBlockState()));
        check(index.snapshot(section).states().get(local).is(Blocks.CHEST), "reloaded chunk overrides stale derived state");
        for (int i = 0; i < 32768; i++) index.changed(new BlockPos(i % 256, 96 + i / 4096, (i / 256) % 16),
                Blocks.REDSTONE_WIRE.defaultBlockState());
        int total = index.dirtySnapshots(Integer.MAX_VALUE).stream().mapToInt(s -> s.states().size()).sum();
        check(total == 32769, "32768 states are retained beyond the legacy 1536 capacity");
        check(index.dirtySnapshots(0).isEmpty(), "backpressure defers rather than drops state");
        check(new BlockSectionIndex().dirtySnapshots(10).isEmpty(), "new save/session does not inherit old sections");
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        int states = 0;
        for (var block : BuiltInRegistries.BLOCK) for (var state : block.getStateDefinition().getPossibleStates()) {
            check(BlockStateKey.decode(BlockStateKey.encode(state)) == state, "stable state roundtrip " + state);
            states++;
        }
        invalid("minecraft:no_such_block"); invalid("minecraft:stone[powered=true]");
        invalid("minecraft:stone_button[powered=maybe]"); invalid("minecraft:stone trailing"); invalid("minecraft:stone{}");
        var world = EmptyBlockGetter.INSTANCE; var pos = BlockPos.ZERO;
        var button = BlockGeometry.at(world, pos, Blocks.STONE_BUTTON.defaultBlockState());
        var wire = BlockGeometry.at(world, pos, Blocks.REDSTONE_WIRE.defaultBlockState());
        check(button.collision().isEmpty() && !button.outline().isEmpty(), "button selectable without cube collision");
        check(wire.collision().isEmpty() && !wire.outline().isEmpty(), "wire selectable without cube collision");
        var piston = Blocks.PISTON.defaultBlockState();
        check(piston.isCollisionShapeFullBlock(world, pos) &&
                !piston.setValue(BlockStateProperties.EXTENDED, true).isCollisionShapeFullBlock(world, pos),
                "legacy full-shape filter removes the extended piston base");
        revisions();
        dev.sifucraft.client.BlockShapesOfflineTest.run();
        Path report = Path.of(args.length == 0 ? "build/reports/p8/block-catalog.json" : args[0]);
        Files.createDirectories(report.toAbsolutePath().getParent()); RedstoneCatalog.write(report);
        System.out.println("P8 offline PASS: " + states + " installed state roundtrips, separate shapes, revisions/tombstones, 32768 blocks; catalog=" + report);
    }
}
