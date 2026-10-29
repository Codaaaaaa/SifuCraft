package dev.sifucraft.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sifucraft.blocks.BlockGeometry;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.PlacedBlocks;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.storage.AtomicFiles;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** S0 vanilla tick trace in a disposable save. Never enables native hooks or edits ordinary saves. */
final class RedstoneCheck {
    private final Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
    private final JsonArray trace = new JsonArray();
    private final JsonArray tickTimes = new JsonArray();
    private final BlockPos piston = new BlockPos(16, 201, 16), sticky = piston.south(5), button = piston.south(10);
    private ServerLevel level;
    private ServerPlayer player;
    private int tick;
    private boolean done, movingSeen, extendedSeen, buttonSeen, lampSeen, buttonReleased;
    private long lastTickNs;

    RedstoneCheck() {
        if (!DevWorld.LEVEL_ID.startsWith("sifucraft-redstone-check-"))
            throw new IllegalStateException("redstoneSelfTest requires sifucraft-redstone-check-* disposable save");
    }

    private static void require(boolean ok, String why) {
        if (!ok) throw new AssertionError("P8 redstone: " + why);
    }

    private void set(BlockPos at, BlockState state) {
        originals.putIfAbsent(at.immutable(), level.getBlockState(at));
        level.setBlockAndUpdate(at, state);
    }

    private void prepare() {
        // Remember the entire swept region, including positions changed by vanilla pistons.
        for (var base : new BlockPos[]{piston, sticky, button}) {
            for (int x = -2; x <= 4; x++) for (int y = -1; y <= 2; y++) for (int z = -1; z <= 1; z++) {
                var at = base.offset(x, y, z);
                require(!level.getBlockState(at).hasBlockEntity(), "fixture requires an empty disposable region");
            }
        }
        for (var base : new BlockPos[]{piston, sticky, button})
            for (int x = -2; x <= 4; x++) for (int y = -1; y <= 2; y++) for (int z = -1; z <= 1; z++) {
                var at = base.offset(x, y, z); originals.put(at, level.getBlockState(at));
            }
        for (var base : new BlockPos[]{piston, sticky}) {
            set(base, (base.equals(piston) ? Blocks.PISTON : Blocks.STICKY_PISTON).defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST));
            set(base.east(), Blocks.STONE.defaultBlockState());
        }
        set(button.below(), Blocks.STONE.defaultBlockState());
        set(button.east(), Blocks.REDSTONE_LAMP.defaultBlockState());
        set(button, Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
        LoggerFactory.getLogger("sifucraft").info("[redstone-test] start vanilla button/lamp and piston/sticky trace; derived_index={}", WorldBlocks.ENABLED);
    }

    private JsonObject observe(BlockPos at) {
        var state = level.getBlockState(at); var geometry = BlockGeometry.at(level, at, state);
        var row = new JsonObject(); row.addProperty("state", BlockStateKey.encode(state));
        row.addProperty("legacy_full_cube", state.isCollisionShapeFullBlock(level, at));
        row.addProperty("collision_boxes", geometry.collision().size());
        row.addProperty("outline_boxes", geometry.outline().size()); row.addProperty("support_boxes", geometry.support().size());
        boolean legacy = PlacedBlocks.between(at.getX(), at.getX() + 1).stream().anyMatch(block -> block.pos().equals(at));
        row.addProperty("legacy_indexed", legacy);
        if (WorldBlocks.ENABLED) {
            var section = WorldBlocks.index().snapshot(SectionPos.of(at).asLong());
            var indexed = section == null ? null : section.states().get(SectionPos.sectionRelativePos(at));
            require(state.isAir() ? indexed == null : indexed == state, "derived section mismatch at " + at + " state=" + state);
            row.addProperty("section_revision", section == null ? 0 : section.revision());
            row.addProperty("section_indexed", indexed != null);
        }
        var entity = level.getBlockEntity(at);
        if (entity != null) row.addProperty("block_entity", entity.getClass().getSimpleName());
        if (entity instanceof PistonMovingBlockEntity moving) {
            row.addProperty("moved_state", BlockStateKey.encode(moving.getMovedState()));
            row.addProperty("progress", moving.getProgress(1)); row.addProperty("extending", moving.isExtending());
            movingSeen = true;
        }
        if ((state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) && state.getValue(BlockStateProperties.EXTENDED)) {
            extendedSeen = true; require(!legacy, "legacy should expose its known extended-base omission");
        }
        if (state.is(Blocks.STONE_BUTTON)) {
            require(!legacy && geometry.collision().isEmpty() && !geometry.outline().isEmpty(), "button legacy/shape reproduction");
            if (state.getValue(BlockStateProperties.POWERED)) buttonSeen = true;
            else if (buttonSeen) buttonReleased = true;
        }
        if (state.is(Blocks.REDSTONE_LAMP) && state.getValue(BlockStateProperties.LIT)) lampSeen = true;
        return row;
    }

    void tick(MinecraftServer server) {
        if (done || server.getPlayerList().getPlayers().isEmpty()) return;
        try {
            if (level == null) {
                level = server.overworld(); player = server.getPlayerList().getPlayers().getFirst(); prepare();
            }
            long now = System.nanoTime();
            if (lastTickNs != 0) tickTimes.add((now - lastTickNs) / 1_000_000.0); lastTickNs = now;
            if (tick == 2) level.getBlockState(button).useWithoutItem(level, player,
                    new BlockHitResult(Vec3.atCenterOf(button), Direction.UP, button, false));
            if (tick == 3 || tick == 12) for (var base : new BlockPos[]{piston, sticky})
                set(base.west(), (tick == 3 ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState());
            var frame = new JsonObject(); frame.addProperty("tick", tick);
            for (var entry : Map.of("piston", piston, "sticky", sticky, "button", button, "lamp", button.east()).entrySet())
                frame.add(entry.getKey(), observe(entry.getValue()));
            for (var entry : Map.of("normal", piston, "sticky", sticky).entrySet()) {
                frame.add(entry.getKey() + "_head", observe(entry.getValue().east()));
                frame.add(entry.getKey() + "_target", observe(entry.getValue().east(2)));
            }
            trace.add(frame);
            if (++tick < 65) return;
            require(movingSeen && extendedSeen && buttonSeen && lampSeen && buttonReleased, "missing lifecycle observation");
            require(level.getBlockState(piston.east()).isAir() && level.getBlockState(piston.east(2)).is(Blocks.STONE), "normal piston final state");
            require(level.getBlockState(sticky.east()).is(Blocks.STONE) && level.getBlockState(sticky.east(2)).isAir(), "sticky piston pulls block back");
            require(!level.getBlockState(button).getValue(BlockStateProperties.POWERED) &&
                    !level.getBlockState(button.east()).getValue(BlockStateProperties.LIT), "button/lamp release");
            var report = new JsonObject(); report.addProperty("schema", 1); report.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().name());
            report.addProperty("derived_index", WorldBlocks.ENABLED); report.addProperty("native_observed", false);
            report.add("trace", trace); report.add("end_tick_interval_ms", tickTimes);
            report.addProperty("result", "PASS");
            var target = server.getWorldPath(LevelResource.ROOT).resolve("p8-redstone-trace.json");
            AtomicFiles.write(target, out -> new GsonBuilder().setPrettyPrinting().create().toJson(report, out));
            restore(server); done = true;
            LoggerFactory.getLogger("sifucraft").info("[redstone-test] PASS 65 vanilla ticks; button press/release/lamp, normal/sticky extend/moving-BE/retract; legacy omissions reproduced; derived_index={}; trace={}", WorldBlocks.ENABLED, target);
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
        } catch (IOException | RuntimeException | AssertionError e) {
            LoggerFactory.getLogger("sifucraft").error("[redstone-test] FAIL tick=" + tick, e);
            restore(server); done = true;
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
            throw new IllegalStateException("P8 redstone fixture failed", e);
        }
    }

    void restore(MinecraftServer server) {
        if (level == null || originals.isEmpty()) return;
        // Clear moving block entities before restoring in one quiet pass. Only this test region.
        for (var at : originals.keySet()) level.removeBlockEntity(at);
        for (var entry : originals.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 2);
        originals.clear();
        LoggerFactory.getLogger("sifucraft").info("[redstone-test] disposable fixture restored");
    }
}
