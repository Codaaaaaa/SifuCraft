package dev.sifucraft.client;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Actual vanilla terrain pixels/depth and cleared-frame controls, in a disposable world only. */
final class BlockModelCheck {
    private static BlockModelCheck current;
    private record Case(String name, BlockState state) {}
    private static final List<Case> CASES = List.of(
        new Case("button",Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH)),
        new Case("button_pressed",Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH).setValue(BlockStateProperties.POWERED,true)),
        new Case("wire",Blocks.REDSTONE_WIRE.defaultBlockState()),
        new Case("wire_powered",Blocks.REDSTONE_WIRE.defaultBlockState().setValue(BlockStateProperties.POWER,15)),
        new Case("slab",Blocks.OAK_SLAB.defaultBlockState()),
        new Case("stairs",Blocks.OAK_STAIRS.defaultBlockState()),
        new Case("door_closed",Blocks.OAK_DOOR.defaultBlockState()),
        new Case("door_open",Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN,true)),
        new Case("piston",Blocks.PISTON.defaultBlockState()),
        new Case("piston_extended",Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.EXTENDED,true)),
        new Case("glass",Blocks.GLASS.defaultBlockState()));
    private final Map<BlockPos,BlockState> originals = new LinkedHashMap<>();
    private volatile boolean prepared;
    private boolean started, done, captured, oldHud;
    private int sample = -1;
    private boolean empty = true;
    private long at, deadline, lastHash;
    private BlockPos target;

    BlockModelCheck() {
        if (!DevWorld.LEVEL_ID.startsWith("sifucraft-block-model-check-")) throw new IllegalStateException("block model test requires disposable save");
        current = this;
    }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError("block model: "+why); }
    private void prepare(Minecraft mc) {
        prepared = false; captured = false; at = System.currentTimeMillis();
        mc.getSingleplayerServer().execute(() -> {
            var level = mc.getSingleplayerServer().overworld();
            originals.putIfAbsent(target,level.getBlockState(target));
            require(!level.getBlockState(target).hasBlockEntity(),"test region has no block entity");
            // Rendering fixture only: preserve the requested state without vanilla wire
            // recomputing its unsupported test input before the renderer can sample it.
            level.setBlock(target,empty ? Blocks.AIR.defaultBlockState() : CASES.get(sample).state,
                Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE|Block.UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE);
            prepared = true;
        });
    }
    void tick(Minecraft mc, boolean ready) {
        if (done || !ready || mc.player == null || mc.gui.screen() != null) return;
        long now = System.currentTimeMillis();
        if (!started) {
            require(BlockShapes.ENABLED,"shape channel required"); started = true; deadline = now+90000;
            oldHud = mc.gui.hud.isHidden(); if (!oldHud) mc.gui.hud.toggle();
            target = BlockPos.containing(mc.player.position().add(mc.player.getLookAngle().scale(3))).above();
            prepare(mc); return;
        }
        require(now < deadline,"deadline sample="+sample+" empty="+empty);
        if (!prepared || now-at < 800 || !SifuCraftClient.blockCollisionCurrent()) return;
        BlockState expected = empty ? Blocks.AIR.defaultBlockState() : CASES.get(sample).state;
        if (mc.level.getBlockState(target) != expected || !captured) return;
        if (empty) { sample++; empty = false; }
        else empty = true;
        if (sample == CASES.size()) {
            done = true; mc.getSingleplayerServer().execute(() -> restore(mc.getSingleplayerServer()));
            restoreClient(mc);
            LoggerFactory.getLogger("sifucraft").info("[block-model-test] PASS 11 vanilla model states, paired depth/position, per-case clear controls");
            mc.stop(); return;
        }
        prepare(mc);
    }
    static void inspect(MemorySegment color, MemorySegment depth, int width, int height, WorldLayerCapture.Snapshot camera) {
        var c = current;
        if (c == null || !c.started || c.done || !c.prepared || c.captured || System.currentTimeMillis()-c.at < 1000 ||
            !SifuCraftClient.blockCollisionCurrent()) return;
        int visible = 0, covered = 0, near = 0; long hash = 1;
        var inverse = new org.joml.Matrix4f(camera.projection()).mul(camera.viewRotation()).invert();
        var center = Vec3.atCenterOf(c.target);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            long offset = ((long)y*width+x)*4;
            if (Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE,offset+3)) <= 8) continue;
            visible++; hash = hash*31+color.get(ValueLayout.JAVA_INT,offset);
            float z = depth.get(ValueLayout.JAVA_FLOAT,offset);
            if (!Float.isFinite(z) || z<=0 || z>1) continue;
            covered++;
            var p = inverse.transform(new org.joml.Vector4f(2f*(x+.5f)/width-1,2f*(y+.5f)/height-1,z,1));
            if (new Vec3(camera.x()+p.x/p.w,camera.y()+p.y/p.w,camera.z()+p.z/p.w).distanceToSqr(center)<4) near++;
        }
        if (c.empty) require(visible==0,"cleared terrain must be transparent: "+visible);
        else {
            if (visible<20) return;
            require(covered>=visible*.95 && near>=covered*.99,"depth/position "+CASES.get(c.sample).name+" "+near+"/"+covered+"/"+visible);
            if (c.sample==1 || c.sample==3) require(hash!=c.lastHash,"powered/pressed model must differ");
            c.lastHash=hash;
            WorldEffectsCheck.evidence(color,width,height,"block-"+CASES.get(c.sample).name);
        }
        LoggerFactory.getLogger("sifucraft").info("[block-model-test] case={} visible={} depth={} near={}",c.empty?"empty":CASES.get(c.sample).name,visible,covered,near);
        c.captured=true;
    }
    void restore(MinecraftServer server) {
        for (var entry : originals.entrySet()) server.overworld().setBlock(entry.getKey(),entry.getValue(),2);
        originals.clear();
    }
    void restoreClient(Minecraft mc) { if (started && mc.gui.hud.isHidden()!=oldHud) mc.gui.hud.toggle(); }
}
