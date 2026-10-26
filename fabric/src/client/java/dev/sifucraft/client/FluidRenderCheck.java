package dev.sifucraft.client;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Actual fluid pixels and paired depth, with the production legacy-solid path as a control. */
final class FluidRenderCheck {
    private record Case(String name, BlockState state) {}
    private static final List<Case> CASES = List.of(
        new Case("water_source",Blocks.WATER.defaultBlockState()),
        new Case("water_flow1",Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,1)),
        new Case("water_flow7",Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,7)),
        new Case("water_falling",Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,8)),
        new Case("waterlogged_slab",Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true)),
        new Case("solid_control",Blocks.STONE.defaultBlockState()));
    private static FluidRenderCheck current;
    private final Map<BlockPos,BlockState> originals = new LinkedHashMap<>();
    private volatile boolean prepared;
    private boolean started, done, empty = true, captured, oldHud;
    private int sample = -1;
    private long at, deadline;
    private BlockPos target;

    FluidRenderCheck() {
        if (!DevWorld.LEVEL_ID.startsWith("sifucraft-fluid-render-check-"))
            throw new IllegalStateException("fluid rendering test requires disposable save");
        current = this;
    }
    private static void require(boolean ok,String why) { if (!ok) throw new AssertionError("fluid rendering: "+why); }
    private static boolean ready() {
        return SifuCraftClient.worldLayersActive() && WorldTerrain.hideBackingFloor() &&
                (!BlockShapes.ENABLED || SifuCraftClient.blockCollisionCurrent());
    }
    private void prepare(Minecraft mc) {
        prepared = false; captured = false; at = System.currentTimeMillis();
        mc.getSingleplayerServer().execute(() -> {
            var level = mc.getSingleplayerServer().overworld();
            originals.putIfAbsent(target,level.getBlockState(target));
            require(!level.getBlockState(target).hasBlockEntity(),"fixture has no block entity");
            var neighbour = target.offset(1,0,0);
            originals.putIfAbsent(neighbour,level.getBlockState(neighbour));
            require(!level.getBlockState(neighbour).hasBlockEntity(),"flow neighbour has no block entity");
            // Visual-state fixture: no scheduled flow spread. P9 simulation is tested separately.
            // A neighbouring source gives flow1/flow7 a real nonzero vanilla flow vector.
            level.setBlock(neighbour,!empty && (sample==1 || sample==2) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE|Block.UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE);
            level.setBlock(target,empty ? Blocks.AIR.defaultBlockState() : CASES.get(sample).state,
                    Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE|Block.UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE);
            prepared = true;
        });
    }
    void tick(Minecraft mc,boolean guiReady) {
        if (done || !guiReady || mc.player == null || mc.gui.screen() != null || !ready()) return;
        // Ambient water drips may outlive a removed waterlogged block. This fixture measures
        // fluid surfaces; keep those particles out of its empty-surface controls.
        mc.particleEngine.clearParticles();
        long now = System.currentTimeMillis();
        if (!started) {
            started = true; deadline = now+90000;
            oldHud = mc.gui.hud.isHidden(); if (!oldHud) mc.gui.hud.toggle();
            target = BlockPos.containing(mc.player.position().add(mc.player.getLookAngle().scale(3))).above();
            prepare(mc); return;
        }
        require(now < deadline,"deadline case="+sample+" empty="+empty);
        if (!prepared || !captured || now-at < 2000) return;
        if (empty) { sample++; empty = false; } else empty = true;
        if (sample == CASES.size()) {
            done = true; mc.getSingleplayerServer().execute(() -> restore(mc.getSingleplayerServer()));
            restoreClient(mc);
            LoggerFactory.getLogger("sifucraft").info("[fluid-render-test] PASS source/flow1/flow7/falling/waterlogged, solid control, paired depth/position and deletion; blockShapes={}",BlockShapes.ENABLED);
            mc.stop(); return;
        }
        prepare(mc);
    }
    static void inspect(MemorySegment color,MemorySegment depth,int width,int height,WorldLayerCapture.Snapshot camera) {
        var c = current;
        if (c == null || !c.started || c.done || !c.prepared || c.captured || System.currentTimeMillis()-c.at < 1000 || !ready()) return;
        var mc = Minecraft.getInstance();
        BlockState expected = c.empty ? Blocks.AIR.defaultBlockState() : CASES.get(c.sample).state;
        if (mc.level.getBlockState(c.target) != expected) return;
        if (!c.empty && (c.sample==1 || c.sample==2)) {
            if (!mc.level.getFluidState(c.target.offset(1,0,0)).isSource()) return;
            require(mc.level.getFluidState(c.target).getFlow(mc.level,c.target).horizontalDistanceSqr()>0,"flow vector must be nonzero");
        }
        int visible=0, covered=0, near=0;
        var inverse = new org.joml.Matrix4f(camera.projection()).mul(camera.viewRotation()).invert();
        var center = Vec3.atCenterOf(c.target);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            long offset = ((long)y*width+x)*4;
            if (Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE,offset+3)) <= 8) continue;
            visible++;
            float z = depth.get(ValueLayout.JAVA_FLOAT,offset);
            if (!Float.isFinite(z) || z<=0 || z>1) continue;
            covered++;
            var p = inverse.transform(new org.joml.Vector4f(2f*(x+.5f)/width-1,2f*(y+.5f)/height-1,z,1));
            if (new Vec3(camera.x()+p.x/p.w,camera.y()+p.y/p.w,camera.z()+p.z/p.w).distanceToSqr(center)<4) near++;
        }
        boolean transparent = c.empty || (c.sample == CASES.size()-1 && !BlockShapes.ENABLED);
        if (transparent) {
            if (visible != 0) WorldEffectsCheck.evidence(color,width,height,"fluid-unexpected-"+(c.empty?"empty":"solid"));
            require(visible==0,"empty/legacy solid must remain transparent: "+visible);
        }
        else {
            if (visible<20) return;
            require(covered>=visible*.95 && near>=covered*.99,"depth/position "+CASES.get(c.sample).name+" "+near+"/"+covered+"/"+visible);
            WorldEffectsCheck.evidence(color,width,height,"fluid-"+CASES.get(c.sample).name);
        }
        LoggerFactory.getLogger("sifucraft").info("[fluid-render-test] case={} visible={} depth={} near={} blockShapes={}",c.empty?"empty":CASES.get(c.sample).name,visible,covered,near,BlockShapes.ENABLED);
        c.captured = true;
    }
    void restore(MinecraftServer server) {
        for (var entry : originals.entrySet()) server.overworld().setBlock(entry.getKey(),entry.getValue(),2);
        originals.clear();
    }
    void restoreClient(Minecraft mc) { if (started && mc.gui.hud.isHidden()!=oldHud) mc.gui.hud.toggle(); }
}
