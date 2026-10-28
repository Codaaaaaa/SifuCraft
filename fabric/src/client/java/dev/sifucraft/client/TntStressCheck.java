package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.combat.SceneExplosions;
import dev.sifucraft.storage.AtomicFiles;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Registered original TNT/world ticks and completed explosion UUIDs, three pressure tiers. */
final class TntStressCheck {
    private final int[] tiers={8,32,128};private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();
    private final Set<UUID> expected=new HashSet<>(),exploded=new HashSet<>();private final List<PrimedTnt> entities=new ArrayList<>();
    private final List<Map<String,Object>> rows=new ArrayList<>();private final List<Double> times=new ArrayList<>();
    private final Set<ChunkPos> forced=new HashSet<>();private final Set<BlockPos> body=new HashSet<>();
    private volatile SifuFrame frame;private volatile boolean finished;private ServerLevel level;private BlockPos origin;private int stage,tier,ticks,duplicates;
    private long began=System.currentTimeMillis(),start,wall,worldStart;private float hp;
    TntStressCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-tnt-stress-check-"),"disposable save");SceneExplosions.observe(id->{if(expected.contains(id)&&!exploded.add(id))duplicates++;});}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("TNT pressure: "+why);}
    void beginTick(MinecraftServer ignored){start=System.nanoTime();}
    void clientTick(Minecraft mc,SifuFrame snapshot){frame=snapshot;if(finished)mc.stop();}
    private void put(BlockPos p,BlockState state){require(!level.getBlockState(p).hasBlockEntity(),"no original container touched");originals.putIfAbsent(p.immutable(),level.getBlockState(p));level.setBlockAndUpdate(p,state);if(state.is(Blocks.OBSIDIAN))body.add(p.immutable());}
    void tick(MinecraftServer server){
        if(finished)return;
        try{
            if(stage==2)times.add((System.nanoTime()-start)/1e6);
            require(System.currentTimeMillis()-began<300000,"bounded complete pressure test");
            var v=WorldMotionWorld.current();var snapshot=frame;
            if(v==null||v.nativeStatus()==null||v.nativeStatus().flags()!=0||v.placement().map().equals("MotionFixture")||(stage<2&&!v.shapesCurrent())||snapshot==null||!snapshot.hasPawn())return;
            level=server.overworld();
            if(stage==0){
                var p=snapshot.playerLocation();origin=BlockPos.containing(v.placement().mcX(p.x())+24,v.placement().originY()+64,v.placement().mcZ(p.y())+8);hp=snapshot.health();
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){var c=new ChunkPos((origin.getX()>>4)+x,(origin.getZ()>>4)+z);if(level.setChunkForced(c.x(),c.z(),true))forced.add(c);}
                for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=0;y<=4;y++)put(origin.offset(x,y,z),(y==0||Math.abs(x)==3||Math.abs(z)==3?Blocks.OBSIDIAN:y<=3?Blocks.WATER:Blocks.AIR).defaultBlockState());
                stage=1;return;
            }
            require(snapshot.health()==hp,"Native player stays outside blast");
            if(stage==1){if(!SceneFluidGeometry.knownVolume(level,new AABB(origin.offset(-10,-8,-10)).minmax(new AABB(origin.offset(10,12,10)))))return;
                if(++ticks<60)return;expected.clear();exploded.clear();entities.clear();times.clear();duplicates=0;ticks=0;worldStart=level.getGameTime();wall=System.nanoTime();
                for(int i=0;i<tiers[tier];i++){
                    var p=Vec3.atBottomCenterOf(origin.above(1));var tnt=new PrimedTnt(level,p.x+(i%4)*.03,p.y,p.z+(i/4%4)*.03,null);
                    require(tnt.getFuse()==80,"original constructor fuse80");expected.add(tnt.getUUID());entities.add(tnt);require(level.addFreshEntity(tnt),"register each original TNT once");
                }
                stage=2;org.slf4j.LoggerFactory.getLogger("sifucraft").info("[tnt-pressure-test] spawned tier={} UUIDs={}",tiers[tier],expected.size());return;
            }
            ticks=(int)(level.getGameTime()-worldStart);
            require(ticks<600,"all TNT finish without silent loss");
            if(entities.stream().anyMatch(e->!e.isRemoved()))return;
            require(exploded.equals(expected)&&duplicates==0,"every TNT has exactly one completed original explosion");
            require(level.getFluidState(origin.above(1)).isSource(),"water source survives tier");
            for(var p:body)require(level.getBlockState(p).is(Blocks.OBSIDIAN),"protected body survives");
            var sorted=times.stream().sorted().toList();double elapsed=(System.nanoTime()-wall)/1e9;
            var row=new LinkedHashMap<String,Object>();row.put("active_tnt",tiers[tier]);row.put("original_explosions",exploded.size());row.put("duplicates",duplicates);row.put("world_ticks",ticks);row.put("wall_seconds",elapsed);row.put("tick_window_tps",ticks/elapsed);row.put("server_tick_p50_ms",sorted.get(sorted.size()/2));row.put("server_tick_p95_ms",sorted.get(Math.min(sorted.size()-1,(int)(sorted.size()*.95))));row.put("server_tick_max_ms",sorted.getLast());row.put("world_rpc_pending",v.requests().pending());rows.add(row);
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[tnt-pressure-test] completed {}",row);
            if(++tier<tiers.length){stage=1;ticks=0;return;}
            AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p10-tnt-pressure.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"backend","real Sifu bridge, original TNT/world ticks; elevated containment, not ground NPC damage or worst-case GPU","tiers",rows),out));
            restore(server);finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").info("[tnt-pressure-test] PASS all 8/32/128 original explosions exactly once, contained water, cleanup");
        }catch(Exception|AssertionError error){restore(server);finished=true;org.slf4j.LoggerFactory.getLogger("sifucraft").error("[tnt-pressure-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    void restore(MinecraftServer ignored){SceneExplosions.observe(null);for(var e:entities)if(!e.isRemoved())e.discard();if(level!=null){for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);for(var c:forced)level.setChunkForced(c.x(),c.z(),false);}originals.clear();forced.clear();}
}
