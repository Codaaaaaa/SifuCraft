package dev.sifucraft.client;

import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldWriter;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Actual captured camera fog, with optional ordinary F8 in the real Native scene. */
final class FluidAtmosphereCheck {
    private final boolean live=Boolean.getBoolean("sifucraft.fluidAtmosphereLive");
    private static FluidAtmosphereCheck current;
    private static final java.nio.file.Path CONTROL=java.nio.file.Path.of("../../build/p8-native-player-f8-control.txt"),CASE=java.nio.file.Path.of("../../build/p9-fluid-fog-case.txt");
    private final Map<BlockPos,BlockState> originals=new LinkedHashMap<>();private final List<Map<String,Object>> reports=new ArrayList<>();
    private volatile SifuFrame frame;private volatile boolean ready,finished,returned;private volatile WorldWriter.Fog fog;private volatile int matching;
    private volatile int expected=-1;private int stage,scenario;private long deadline,at,stableAt;private float nativeHealth,health;private boolean restored;
    private ServerPlayer player;private ServerLevel level;private BlockPos base;private Vec3 original;private float yaw,pitch;private MobEffectInstance oldFire,oldBreath;
    private dev.sifucraft.bridge.WorldMotionRequests.Scope scope;
    FluidAtmosphereCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-fluid-fog-check-"),"disposable save required");current=this;}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("fluid atmosphere: "+why);}
    private static void write(java.nio.file.Path file,String value){try{java.nio.file.Files.writeString(file,value);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    static void published(WorldWriter.Fog value){var c=current;if(c!=null){c.fog=value;if(value.kind()==c.expected)c.matching++;else c.matching=0;}}
    void clientTick(Minecraft mc,SifuFrame snapshot){frame=snapshot;if(finished){mc.stop();return;}
        if(live&&stage==0&&mc.player!=null&&snapshot!=null&&!snapshot.minecraftOwnsInput())write(CONTROL,"1:mc");
        // Native can finish its normal initial handoff before the external driver starts.
        // Cancel the old request rather than toggling the now-correct owner away again.
        if(live&&snapshot!=null&&snapshot.minecraftOwnsInput()&&stage<=1){try{if(java.nio.file.Files.exists(CONTROL)&&java.nio.file.Files.readString(CONTROL).equals("1:mc"))write(CONTROL,"0:idle");}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
        var v=WorldMotionWorld.current();ready=v!=null&&v.shapesCurrent()&&SceneFluidGeometry.available(mc.level)&&(live?!v.placement().map().equals("MotionFixture"):v.placement().map().equals("MotionFixture"));
        returned=original!=null&&mc.player!=null&&mc.player.position().distanceTo(original)<.02;
    }
    private void put(BlockPos pos,BlockState state){var old=level.getBlockState(pos);require(!old.hasBlockEntity(),"no original BE replaced");originals.putIfAbsent(pos.immutable(),old);level.setBlock(pos,state,2);}
    private void build(){for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
        put(base.offset(x,0,z),Blocks.GLASS.defaultBlockState());for(int y=1;y<=5;y++)put(base.offset(x,y,z),Math.abs(x)==2||Math.abs(z)==2||y==5?Blocks.GLASS.defaultBlockState():Blocks.AIR.defaultBlockState());
    }}
    private void teleport(Vec3 pos){player.teleportTo(level,pos.x,pos.y,pos.z,java.util.Set.of(),yaw,pitch,true);}
    private void prepare(){
        var material=scenario==1?Blocks.WATER:scenario==2?Blocks.LAVA:Blocks.AIR;
        // Remove the previous fluid first; sequential water->lava replacement would legitimately mix.
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=1;y<=4;y++)put(base.offset(x,y,z),Blocks.AIR.defaultBlockState());
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=1;y<=4;y++)put(base.offset(x,y,z),material.defaultBlockState());
        teleport(Vec3.atBottomCenterOf(base.above()).add(0,.01,0));
        expected=scenario==1?1:scenario==2?2:0;matching=0;at=System.currentTimeMillis();stage=1;write(CASE,scenario==1?"water_mc":scenario==2?"lava_mc":scenario==0?"dry_mc":"dry_return");
    }
    private void record(String name){require(fog!=null&&fog.kind()==expected&&matching>=5,"paired frame metadata "+name);reports.add(Map.of("case",name,"fog",fog.toString(),"paired_frames",matching));LoggerFactory.getLogger("sifucraft").info("[fluid-fog-test] {} paired={} {}",name,matching,fog);}
    void tick(MinecraftServer server){if(finished)return;try{
        var snapshot=frame;if(snapshot==null)return;
        if(stage==0){if(!ready||!snapshot.minecraftOwnsInput()||server.getPlayerList().getPlayers().isEmpty())return;
            level=server.overworld();player=server.getPlayerList().getPlayers().getFirst();original=player.position();yaw=player.getYRot();pitch=player.getXRot();health=player.getHealth();nativeHealth=snapshot.health();
            scope=WorldMotionWorld.current().scope();
            var effect=player.getEffect(MobEffects.FIRE_RESISTANCE);oldFire=effect==null?null:new MobEffectInstance(effect);player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,20000));
            var breath=player.getEffect(MobEffects.WATER_BREATHING);oldBreath=breath==null?null:new MobEffectInstance(breath);player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING,20000));
            base=new BlockPos((int)Math.floor(original.x),(int)Math.ceil(original.y)-1,(int)Math.floor(original.z));deadline=System.currentTimeMillis()+120000;build();prepare();return;
        }
        require(System.currentTimeMillis()<deadline,"bounded fixture stage="+stage+" scenario="+scenario+" matching="+matching);
        require(player.isAlive()&&!player.isRemoved(),"original test player remains alive");
        var currentView=WorldMotionWorld.current();if(currentView!=null)require(scope.equals(currentView.scope()),"same Native world and activation");
        if(live)require(snapshot.health()==nativeHealth,"Native health unchanged during MC environment display");
        if(stage==1){if(!snapshot.minecraftOwnsInput()||!ready||matching<5||System.currentTimeMillis()-at<3000)return;record(scenario==1?"water_mc":scenario==2?"lava_mc":scenario==0?"dry_mc":"dry_return");
            if(live&&scenario==1){matching=0;write(CONTROL,"2:native");stage=2;return;}
            next(server);return;
        }
        if(stage==2){if(snapshot.minecraftOwnsInput())return;if(stableAt==0){stableAt=System.currentTimeMillis();matching=0;write(CASE,"water_native");}if(!ready||matching<5||System.currentTimeMillis()-stableAt<3000)return;record("water_native");write(CONTROL,"3:mc");stage=3;return;}
        if(stage==3){if(snapshot.minecraftOwnsInput()&&ready){scenario++;prepare();}return;}
        if(stage==4){if(!ready||!returned)return;
            var v=WorldMotionWorld.current();
            var actual=v.placement();double error=Math.sqrt(Math.pow(actual.mcX(snapshot.playerLocation().x())-original.x,2)+Math.pow(actual.mcY(snapshot.playerLocation().z()-snapshot.capsuleHalfHeight())-original.y,2)+Math.pow(actual.mcZ(snapshot.playerLocation().y())-original.z,2));
            if(error>.03)return;if(live){write(CONTROL,"4:native");stableAt=0;stage=5;return;}finish(server);return;
        }
        if(stage==5){if(snapshot.minecraftOwnsInput())return;if(stableAt==0)stableAt=System.currentTimeMillis();if(System.currentTimeMillis()-stableAt>=3000)finish(server);}
    }catch(Exception|AssertionError e){LoggerFactory.getLogger("sifucraft").error("[fluid-fog-test] FAIL",e);restore(server);finished=true;write(CONTROL,"0:done");Minecraft.getInstance().execute(()->{throw new AssertionError(e);});}}
    private void next(MinecraftServer server){if(++scenario<4){prepare();return;}restore(server);stage=4;}
    private void finish(MinecraftServer server){try{AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p9-fluid-fog.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("result","PASS","actual_ue",live,"cases",reports),out));}catch(Exception e){throw new IllegalStateException(e);}finished=true;write(CONTROL,"0:done");write(CASE,"done");LoggerFactory.getLogger("sifucraft").info("[fluid-fog-test] PASS actual captured dry/water/lava/exit, restored; live={}",live);}
    void restore(MinecraftServer ignored){if(restored)return;restored=true;expected=0;matching=0;if(player!=null){teleport(original);player.clearFire();player.setHealth(health);player.removeEffect(MobEffects.FIRE_RESISTANCE);if(oldFire!=null)player.addEffect(oldFire);player.removeEffect(MobEffects.WATER_BREATHING);if(oldBreath!=null)player.addEffect(oldBreath);}if(level!=null)for(var e:originals.entrySet())level.setBlock(e.getKey(),e.getValue(),2);originals.clear();}
}
