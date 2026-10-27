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
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Fixed camera, dense installed fluid/solid meshes. Visual-state benchmark, not a fluid TPS test. */
final class WorldGpuCheck {
    private static final List<String> CASES=List.of("empty","water_source","water_flow5","solid_control");
    private static final long HOLD_MS=15000;
    private static WorldGpuCheck current;
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Map<String,Long> pixels=new LinkedHashMap<>();
    private volatile boolean prepared,finished;private boolean started,done,sampled,oldHud;private int phase;
    private long at,deadline;private BlockPos center;private Direction forward;
    private Vec3 fixedPosition;private float fixedYaw,fixedPitch;
    WorldGpuCheck(){if(!DevWorld.LEVEL_ID.startsWith("sifucraft-world-gpu-check-"))throw new IllegalStateException("disposable GPU fixture required");current=this;}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("world GPU: "+why);}
    private static boolean ready(){return SifuCraftClient.worldLayersActive()&&WorldTerrain.hideBackingFloor()&&SifuCraftClient.blockCollisionCurrent();}
    private BlockState state(){return switch(phase){case 1->Blocks.WATER.defaultBlockState();case 2->Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,5);case 3->Blocks.STONE.defaultBlockState();default->Blocks.AIR.defaultBlockState();};}
    private void prepare(Minecraft mc){
        prepared=false;sampled=false;at=System.currentTimeMillis();
        mc.getSingleplayerServer().execute(()->{
            var level=mc.getSingleplayerServer().overworld();var side=forward.getClockWise();
            for(int y=-2;y<=6;y++)for(int x=-4;x<=4;x++)for(int layer=0;layer<=1;layer++){
                var pos=center.above(y).relative(side,x).relative(forward,layer);var old=level.getBlockState(pos);
                require(!old.hasBlockEntity(),"does not replace a saved BE");originals.putIfAbsent(pos.immutable(),old);
                var next=layer==0?state():phase==2?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
                level.setBlock(pos,next,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SKIP_ON_PLACE|Block.UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE);
            }
            prepared=true;LoggerFactory.getLogger("sifucraft").info("[world-gpu-test] phase={} begin fixed-camera visual-state panel, hold_ms={} wall_clock_ms={}",CASES.get(phase),HOLD_MS,System.currentTimeMillis());
        });
    }
    void tick(Minecraft mc,boolean gui){
        if(done){if(finished)mc.stop();return;}
        if(!gui||mc.player==null||mc.gui.screen()!=null||!ready())return;
        if(fixedPosition!=null)require(mc.player.position().distanceToSqr(fixedPosition)<1e-6&&Math.abs(Mth.wrapDegrees(mc.player.getYRot()-fixedYaw))<.001&&Math.abs(mc.player.getXRot()-fixedPitch)<.001,"benchmark camera moved: initial="+fixedPosition+" yaw="+fixedYaw+" pitch="+fixedPitch+" actual="+mc.player.position()+" yaw="+mc.player.getYRot()+" pitch="+mc.player.getXRot());
        mc.particleEngine.clearParticles();long now=System.currentTimeMillis();
        if(!started){started=true;deadline=now+150000;oldHud=mc.gui.hud.isHidden();if(!oldHud)mc.gui.hud.toggle();
            forward=Direction.fromYRot(mc.player.getYRot());center=BlockPos.containing(mc.player.getEyePosition().add(mc.player.getLookAngle().scale(1.5)));prepare(mc);return;}
        require(now<deadline,"deadline phase="+phase+" sampled="+sampled);
        if(!prepared||!sampled||now-at<HOLD_MS)return;
        phase++;if(phase<CASES.size()){prepare(mc);return;}
        done=true;mc.getSingleplayerServer().execute(()->{
            restore(mc.getSingleplayerServer());
            try{dev.sifucraft.storage.AtomicFiles.write(mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("p9-world-gpu.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","source_pixels",pixels,"hold_ms",HOLD_MS,"kind","fixed visual-state meshes, not simulation or TPS"),out));}
            catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
            LoggerFactory.getLogger("sifucraft").info("[world-gpu-test] PASS fixed camera dense source/flow/solid and empty control; pixels={}",pixels);finished=true;
        });restoreClient(mc);
    }
    static void inspect(MemorySegment color,MemorySegment depth,int width,int height){
        var test=current;if(test==null||!test.started||test.done||!test.prepared||test.sampled||!ready()||System.currentTimeMillis()-test.at<2500)return;
        var mc=Minecraft.getInstance();if(mc.level.getBlockState(test.center)!=test.state())return;
        if(test.phase==2){if(!mc.level.getFluidState(test.center.relative(test.forward)).isSource())return;require(mc.level.getFluidState(test.center).getFlow(mc.level,test.center).horizontalDistanceSqr()>0,"flow control uses a real nonzero vanilla flow vector");}
        long visible=0,covered=0;for(long i=0,end=(long)width*height;i<end;i++)if(Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE,i*4+3))>8){visible++;float d=depth.get(ValueLayout.JAVA_FLOAT,i*4);if(Float.isFinite(d)&&d>0&&d<=1)covered++;}
        if(test.phase==0){require(visible==0,"empty control contains no world-color pixels");test.fixedPosition=mc.player.position();test.fixedYaw=mc.player.getYRot();test.fixedPitch=mc.player.getXRot();LoggerFactory.getLogger("sifucraft").info("[world-gpu-test] camera position={} yaw={} pitch={}",test.fixedPosition,test.fixedYaw,test.fixedPitch);}
        else{require(visible>(long)width*height*.7,"dense panel covers at least 70% of source: "+visible+"/"+width*height);require(covered>=visible*.99,"dense source has valid paired depth");WorldEffectsCheck.evidence(color,width,height,"gpu-"+CASES.get(test.phase));}
        test.pixels.put(CASES.get(test.phase),visible);test.sampled=true;
        LoggerFactory.getLogger("sifucraft").info("[world-gpu-test] phase={} sampled pixels={} depth={} width={} height={}",CASES.get(test.phase),visible,covered,width,height);
    }
    void restore(MinecraftServer server){for(var entry:originals.entrySet())server.overworld().setBlock(entry.getKey(),entry.getValue(),2);originals.clear();}
    void restoreClient(Minecraft mc){if(started&&mc.gui.hud.isHidden()!=oldHud)mc.gui.hud.toggle();}
}
