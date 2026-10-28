package dev.sifucraft.client;

import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.combat.BoatPassengers;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.FluidMotion;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.WorldMotionProtocol;
import dev.sifucraft.storage.AtomicFiles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Vanilla boat + production MC passenger adapter + separate native world-command endpoint. */
final class NativeBoatCheck {
    private final boolean live=Boolean.getBoolean("sifucraft.nativeBoatLive");
    private final Map<EnemyFrame.Key,EnemyFrame.Entry> initial=new HashMap<>();
    private volatile boolean ready,finished;
    private AbstractBoat boat;private Vec3 origin;private int stage,ticks;
    private long deadline,began,initialFailed,initialReleased;private double moved,yawChange;private int initialBoarded;
    private final int renderStallMs=Math.clamp(Integer.getInteger("sifucraft.nativeBoatRenderStallMs",0),0,1500);
    private volatile boolean stallRequested,stallCompleted;
    private java.nio.file.Path threadReport;
    private Vec3 direction=new Vec3(-1,0,0);
    private volatile float playerHealth=Float.NaN;private float initialPlayerHealth;
    NativeBoatCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-boat-passengers-check-"),"disposable save required");}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("native boat: "+why);}
    void clientTick(Minecraft mc,dev.sifucraft.bridge.SifuFrame frame){
        if(frame!=null)playerHealth=frame.health();
        if(finished){mc.stop();return;}
        if(stallRequested&&!stallCompleted){
            java.util.concurrent.CompletableFuture.delayedExecutor(350,java.util.concurrent.TimeUnit.MILLISECONDS).execute(()->{
                try{var text=new StringBuilder();for(var info:java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true,true)){
                    text.append(info.getThreadName()).append(' ').append(info.getThreadState()).append(" lock=").append(info.getLockName()).append(" owner=").append(info.getLockOwnerName()).append('\n');
                    for(var stackFrame:info.getStackTrace())text.append("  ").append(stackFrame).append('\n');
                }java.nio.file.Files.writeString(threadReport,text.toString());}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            });
            try{Thread.sleep(renderStallMs);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            stallCompleted=true;
        }
        var v=WorldMotionWorld.current();ready=v!=null&&v.shapesCurrent()&&v.nativeStatus()!=null&&v.nativeStatus().flags()==0&&
                (v.nativeStatus().capabilities()&WorldMotionProtocol.PASSENGER_CAPABILITY)!=0&&v.enemies()!=null&&
                (live?!v.placement().map().equals("MotionFixture"):v.placement().map().equals("MotionFixture"));
    }
    private EnemyFrame.Entry current(WorldMotionWorld.View view,EnemyFrame.Key key){return view.enemies().entries().stream().filter(e->e.key().equals(key)).findFirst().orElse(null);}
    void tick(MinecraftServer server){
        if(finished)return;
        if(began==0)began=System.currentTimeMillis();
        try{
            require(System.currentTimeMillis()-began<90000,"bounded complete fixture");
            var view=WorldMotionWorld.current();if(stage==0&&!ready)return;
            if(stage==0){
                var candidates=view.enemies().entries().stream().filter(e->e.alive()&&EnemyMirrors.hitAttacker(e.id(),e.spawn())!=null).limit(live?1:2).toList();
                if(candidates.size()<(live?1:2))return;
                began=System.currentTimeMillis();
                threadReport=server.getWorldPath(LevelResource.ROOT).resolve("p11-stalled-render-threads.txt");
                for(var entry:candidates)initial.put(entry.key(),entry);
                initialPlayerHealth=playerHealth;
                double x=0,y=0,z=0;
                for(var entry:initial.values()){x+=view.placement().mcX(entry.location().x());y+=view.placement().mcY(entry.location().z()-entry.halfHeight());z+=view.placement().mcZ(entry.location().y());}
                int count=initial.size();origin=new Vec3(x/count,y/count+.4,z/count);
                var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("oak_boat"));
                boat=(AbstractBoat)type.create(server.overworld(),EntitySpawnReason.COMMAND);require(boat!=null,"installed original boat");
                boat.setPos(origin);require(server.overworld().addFreshEntity(boat),"one registered original boat");
                if(live){
                    direction=null;
                    for(var d:List.of(new Vec3(-1,0,0),new Vec3(1,0,0),new Vec3(0,0,-1),new Vec3(0,0,1))){
                        var swept=boat.getBoundingBox().expandTowards(d.scale(.9)).deflate(.001);
                        boolean blocked=false;for(var shape:server.overworld().getBlockCollisions(boat,swept))if(!shape.isEmpty()){blocked=true;break;}
                        if(!blocked){direction=d;break;}
                    }
                    require(direction!=null,"a short boat path fits the imported scene before moving a real NPC");
                }
                initialBoarded=(int)BoatPassengers.stats().boarded();initialFailed=BoatPassengers.stats().failed();initialReleased=BoatPassengers.stats().released();deadline=System.currentTimeMillis()+20000;stage=1;
                LoggerFactory.getLogger("sifucraft").info("[native-boat-test] placed original boat at {} near {} activations live={}",origin,initial.keySet(),live);return;
            }
            if(!ready)return;
            if(live)require(Float.isFinite(initialPlayerHealth)&&initialPlayerHealth>0&&playerHealth==initialPlayerHealth,"original player HP remains unchanged during the NPC fixture");
            require(System.currentTimeMillis()<deadline,"bounded stage="+stage+" passenger="+BoatPassengers.stats()+" fluid="+FluidMotion.stats());
            for(var entry:initial.values()){
                var e=current(view,entry.key());require(e!=null&&e.alive()&&e.health()==entry.health(),"same activation and HP throughout passenger motion");
                moved=Math.max(moved,Math.sqrt(Math.pow(e.location().x()-entry.location().x(),2)+Math.pow(e.location().y()-entry.location().y(),2))/100.0);
                yawChange=Math.max(yawChange,Math.abs(net.minecraft.util.Mth.wrapDegrees(e.yaw()-entry.yaw())));
            }
            if(stage==1){
                if(boat.getPassengers().size()<initial.size())return;
                require(BoatPassengers.stats().boarded()>=initialBoarded+initial.size(),"MC relationship waited for native lease preparation");
                stage=2;ticks=0;deadline=System.currentTimeMillis()+20000;return;
            }
            if(stage==2){
                require(boat.getPassengers().size()==initial.size(),"native passengers remain attached while moving");
                var distance=new Vec3(boat.getX()-origin.x,0,boat.getZ()-origin.z).length();
                boat.setDeltaMovement(live?(distance<.6?direction.scale(.015):Vec3.ZERO):direction.scale(.06));boat.setYRot(boat.getYRot()+3);
                if(ticks==30&&renderStallMs>0)stallRequested=true;
                if(++ticks<100)return;
                require(moved>(live?.2:.75)&&yawChange>20,"native snapshots actually translated and turned with the boat: moved="+moved+" yaw="+yawChange);
                stage=3;ticks=0;deadline=System.currentTimeMillis()+20000;return;
            }
            if(stage==3){
                var back=new Vec3(origin.x-boat.getX(),0,origin.z-boat.getZ());
                if(back.length()>.08){boat.setDeltaMovement(back.normalize().scale(.08));if(++ticks<250)return;}
                require(back.length()<=.08,"boat returned through original physics to its starting horizontal position");
                boat.discard();stage=4;deadline=System.currentTimeMillis()+15000;return;
            }
            if(stage==4){
                if(BoatPassengers.stats().active()!=0||FluidMotion.stats().active()!=0||view.requests().pending()!=0)return;
                require(BoatPassengers.stats().failed()==initialFailed&&BoatPassengers.stats().released()>=initialReleased+initial.size(),"normal boat removal confirms successful native handback, not a failed lease");
                require(renderStallMs==0||stallCompleted,"explicit stalled-render control actually ran");
                for(var key:initial.keySet())require(!EnemyMirrors.hitAttacker(key.id(),key.spawn()).isPassenger(),"boat deletion removed the MC passenger relation");
                var report=new HashMap<String,Object>();report.put("passed",true);report.put("backend",live?"real Sifu capsule; original-tick recovery still requires behavior observation":"separate C++ AABB endpoint; not a UE capsule");
                report.put("targets",initial.size());report.put("max_native_displacement_m",moved);report.put("native_yaw_change_degrees",yawChange);report.put("passengers",BoatPassengers.stats());report.put("fluid",FluidMotion.stats());
                report.put("render_stall_ms",renderStallMs);
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve("p11-native-boat.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
                LoggerFactory.getLogger("sifucraft").info("[native-boat-test] PASS targets={} moved={} yaw={} passenger={} fluid={}",initial.size(),moved,yawChange,BoatPassengers.stats(),FluidMotion.stats());
                finished=true;
            }
        }catch(Exception|AssertionError error){restore(server);finished=true;LoggerFactory.getLogger("sifucraft").error("[native-boat-test] FAIL",error);Minecraft.getInstance().execute(()->{throw new AssertionError(error);});}
    }
    void restore(MinecraftServer server){if(boat!=null&&!boat.isRemoved())boat.discard();BoatPassengers.stop();}
}
