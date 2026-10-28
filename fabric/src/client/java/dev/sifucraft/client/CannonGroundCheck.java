package dev.sifucraft.client;

import dev.sifucraft.blocks.*;
import dev.sifucraft.bridge.*;
import dev.sifucraft.storage.AtomicFiles;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import org.slf4j.LoggerFactory;

/** Ground-level original geometry and interaction survey before C4's actual blast matrix. */
final class CannonGroundCheck {
    private final String scenario=System.getProperty("sifucraft.cannonGroundScenario","survey");
    private final NativeUse use=new NativeUse();
    private final Map<String,Object> report=new LinkedHashMap<>();
    private volatile SifuFrame frame;private volatile NativeInteraction candidate,observed;private volatile boolean ready,finished;
    private volatile AssertionError failure;private volatile int stage;private long began,lastLog;
    private record Ray(Vec3 from,Vec3 to,boolean before){}
    private final List<Ray> doorRays=new ArrayList<>();private NativeInteraction door;
    private volatile NativeInteraction.Result interactionResult;private volatile boolean pressRequested;
    private volatile long pressedAt;private boolean released;
    private ServerPlayer player;private Vec3 original;private float yaw,pitch,nativeHealth,mcHealth;
    private Vec3 approach;
    private Vec3 safe;private EnemyFrame.Key nativeTarget;private CannonGroundBlast blast;private boolean closedVerified;
    private long matrixObservedAt;
    private long encounterAt;
    CannonGroundCheck(){require(DevWorld.LEVEL_ID.startsWith("sifucraft-cannon-ground-check-"),"disposable ground save required");require(Set.of("survey","door","matrix").contains(scenario),"known ground fixture phase");}
    void interaction(NativeInteraction.Result result){interactionResult=result;}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError("C4 ground: "+why);}
    void clientTick(Minecraft mc,McBridge bridge,InputInjector input,boolean gui){
        if(failure!=null){input.releaseAll();throw failure;}
        frame=bridge.latest();var view=WorldMotionWorld.current();
        ready=gui&&mc.player!=null&&frame!=null&&frame.minecraftOwnsInput()&&view!=null&&view.nativeStatus()!=null&&view.nativeStatus().flags()==0&&view.shapesCurrent();
        observed=bridge.interaction();
        if(stage==1&&ready&&observed!=null&&observed.available(bridge.peerSession(),frame,System.currentTimeMillis())){
            var lo=observed.minimum();var hi=observed.maximum();var p=view.placement();
            var point=new Vec3(p.mcX((lo.x()+hi.x())/2),p.mcY((lo.z()+hi.z())/2),p.mcZ((lo.y()+hi.y())/2));
            var delta=point.subtract(mc.player.getEyePosition());
            mc.player.setYRot((float)Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
            mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))));
        }
        candidate=ready?use.candidate(bridge,mc):null;
        if(pressRequested&&pressedAt==0&&candidate!=null&&door!=null&&candidate.target()==door.target()&&candidate.serial()==door.serial()){
            pressedAt=System.currentTimeMillis();input.mouseButton(3,1,0);
        }
        if(pressedAt!=0&&!released&&System.currentTimeMillis()-pressedAt>=150){input.mouseButton(3,0,0);released=true;}
        if(finished){input.releaseAll();mc.stop();}
    }
    void tick(MinecraftServer server){
        if(finished||failure!=null)return;
        try{
            var view=WorldMotionWorld.current();if(view==null)return;
            if(began==0){if(!ready)return;began=System.currentTimeMillis();}
            require(System.currentTimeMillis()-began<180000,"bounded ground fixture stage="+stage);
            require(view.placement().map().equals("Hideout_1_Main"),"survey is tied to the inspected first map");
            if(stage==0){
                if(!ready)return;
                if(player==null){player=server.getPlayerList().getPlayers().getFirst();original=player.position();yaw=player.getYRot();pitch=player.getXRot();nativeHealth=frame.health();mcHealth=player.getHealth();}
                if(scenario.equals("matrix")&&view.enemies().entries().isEmpty()){
                    // The first encounter is activated by the ordinary player
                    // crossing the start corridor. Going straight to the exit
                    // door after a level restart skips that original trigger.
                    if(encounterAt==0){
                        var at=new Vec3(-167.25,64,29.72);var box=player.getBoundingBox().move(at.subtract(player.position()));
                        if(!SceneFluidGeometry.knownVolume(player.level(),box.inflate(.1)))return;
                        require(dev.sifucraft.collision.SifuCollision.withQueryView(view.scene(),()->player.level().noCollision(player,box))&&view.scene().clip(at.add(0,.01,0),at.add(0,-.35,0))!=null,"supported original encounter approach");
                        teleport(at);encounterAt=System.currentTimeMillis();
                        LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] ordinary player crossed the original start encounter trigger at {}",at);
                    }
                    require(System.currentTimeMillis()-encounterAt<15000,"original encounter produces actual Native targets before any TNT");return;
                }
                int known=0,clear=0;
                search:for(double x:new double[]{-145.75,-144.75,-145.25,-146.25})for(double z:new double[]{62.625,62.125,63.125})for(int step=0;step<=10;step++){
                    var at=new Vec3(x,64+step*.125,z);var box=player.getBoundingBox().move(at.subtract(player.position()));
                    if(!SceneFluidGeometry.knownVolume(player.level(),box.inflate(.1)))continue;
                    known++;boolean fits=dev.sifucraft.collision.SifuCollision.withQueryView(view.scene(),()->player.level().noCollision(player,box));if(fits)clear++;
                    if(fits&&
                        view.scene().clip(at.add(0,.01,0),at.add(0,-.35,0))!=null){approach=at;break search;}
                }
                if(approach==null){long now=System.currentTimeMillis();if(now-lastLog>2000){lastLog=now;LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] waiting actual approach coverage known={} collision_clear={}",known,clear);}return;}
                player.teleportTo(player.level(),approach.x,approach.y,approach.z,Set.of(),-90,0,true);player.setDeltaMovement(Vec3.ZERO);stage=1;
                LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] moved ordinary MC fixture to ground door approach {}",approach);return;
            }
            require(frame!=null&&frame.health()==nativeHealth&&player.getHealth()==mcHealth,"both player health authorities remain unchanged during survey");
            if(stage==1){
                if(!ready)return;
                var c=candidate;long now=System.currentTimeMillis();
                if(now-lastLog>1500){lastLog=now;LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] survey player={} interaction={} raw={} known_door_solid={} native_targets={}",player.position(),c,observed,view.scene().solidAt(-143.4375,65,62.625),view.enemies().entries());}
                if(c==null)return;
                report.put("status","SURVEY");report.put("c4_complete",false);report.put("actual_native_map",view.placement().map());report.put("native_interaction",c);report.put("door_solid_at_known_point",view.scene().solidAt(-143.4375,65,62.625));
                report.put("native_targets",view.enemies().entries());report.put("approach",player.position().toString());
                if(scenario.equals("matrix")&&!closedVerified){
                    if(matrixObservedAt==0){matrixObservedAt=now;return;}
                    if(now-matrixObservedAt<750||!SceneFluidGeometry.available(player.level()))return;
                    if(!dev.sifucraft.combat.SceneExplosions.blocked(view.scene(),new Vec3(-144.0218,65,62.625),new Vec3(-147.0218,65,62.625))){require(now-matrixObservedAt<5000,"matrix begins with the actually closed original door after geometry settled");return;}
                    var target=view.enemies().entries().stream().filter(e->e.alive()&&e.maxHealth()>=100&&e.health()>=80&&e.location().y()>6000&&e.location().y()<6500).findFirst().orElse(null);if(target==null)return;
                    nativeTarget=target.key();
                    search:for(double x:new double[]{-167.25,-158,-157.5,-157,-156,-154,-160})for(double z:new double[]{40,53,55,50,62.625,61.625,63.625})for(int step=0;step<=10;step++){
                        var at=new Vec3(x,64+step*.125,z);var box=player.getBoundingBox().move(at.subtract(player.position()));
                        if(at.distanceTo(new Vec3(-144.0218,64.35,62.625))<=12.5||at.distanceTo(new Vec3(view.placement().mcX(target.location().x()),64,view.placement().mcZ(target.location().y())))>=28)continue;
                        if(!SceneFluidGeometry.knownVolume(player.level(),box.inflate(.1)))continue;
                        if(dev.sifucraft.collision.SifuCollision.withQueryView(view.scene(),()->player.level().noCollision(player,box))&&view.scene().clip(at.add(0,.01,0),at.add(0,-.35,0))!=null){safe=at;break search;}
                    }
                    if(safe==null){require(now-matrixObservedAt<15000,"supported actual ground stand outside the blast radius after coverage loaded");return;}
                    teleport(safe);stage=4;return;
                }
                if(scenario.equals("door")||scenario.equals("matrix")){
                    if(!pressRequested){
                        door=c;var lo=c.minimum();var hi=c.maximum();var p=view.placement();
                        var center=new Vec3(p.mcX((lo.x()+hi.x())/2),p.mcY((lo.z()+hi.z())/2),p.mcZ((lo.y()+hi.y())/2));
                        for(var axis:List.of(new Vec3(1,0,0),new Vec3(0,0,1)))for(int lateral=-16;lateral<=16;lateral++)for(double y:new double[]{64.5,65,65.5}){
                            var at=new Vec3(center.x+axis.z*lateral*.125,y,center.z+axis.x*lateral*.125);
                            var from=at.add(axis.scale(1.5));var to=at.add(axis.scale(-1.5));
                            if(SceneFluidGeometry.knownVolume(player.level(),new AABB(from,to).inflate(.01)))doorRays.add(new Ray(from,to,dev.sifucraft.combat.SceneExplosions.blocked(view.scene(),from,to)));
                        }
                        require(!doorRays.isEmpty(),"actual door vicinity has known ray samples");pressRequested=true;return;
                    }
                    var reply=interactionResult;if(reply==null)return;
                    require(reply.outcome()==0&&reply.target()==door.target()&&reply.world()==door.world()&&reply.epoch()==door.epoch(),"existing Native use dispatched the same actual door identity and scope");
                    stage=3;return;
                }
                restore(server);stage=2;return;
            }
            if(stage==3){
                if(!ready||System.currentTimeMillis()-pressedAt<2000)return;
                var opened=new ArrayList<Ray>();var closed=new ArrayList<Ray>();
                for(var ray:doorRays){boolean after=dev.sifucraft.combat.SceneExplosions.blocked(view.scene(),ray.from,ray.to);if(ray.before&&!after)opened.add(ray);if(!ray.before&&after)closed.add(ray);}
                if(opened.isEmpty()&&closed.isEmpty()){require(System.currentTimeMillis()-pressedAt<10000,"actual door use changed the imported collision, not merely a dispatch receipt");return;}
                report.put("status","DOOR_GEOMETRY");report.put("door_interaction_reply",interactionResult);report.put("closed_to_clear_rays",opened);report.put("clear_to_closed_rays",closed);
                LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] actual door geometry changed clear={} blocked={} after existing use",opened.size(),closed.size());
                if(scenario.equals("matrix")){require(!opened.isEmpty(),"actual closed door opened before the second ground blast");teleport(safe);stage=6;return;}
                restore(server);stage=2;return;
            }
            if(stage==4||stage==6){
                if(!ready||player.position().distanceTo(safe)>.05)return;
                var target=view.enemies().entries().stream().filter(e->e.key().equals(nativeTarget)&&e.alive()).findFirst().orElse(null);if(target==null)return;
                if(!SceneFluidGeometry.blastVolumeReady(player.level(),new AABB(-157,52,49,-131,79,76))){long now=System.currentTimeMillis();if(now-lastLog>2000){lastLog=now;LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] waiting actual complete ground blast coverage stage={}",stage);}return;}
                blast=new CannonGroundBlast(server.overworld(),view,target,stage==6);stage=stage==4?5:7;return;
            }
            if(stage==5||stage==7){
                if(!blast.tick(view))return;
                report.put(stage==5?"closed_blast":"open_blast",blast.report());blast.close();blast=null;
                if(stage==5){closedVerified=true;teleport(approach);stage=1;return;}
                report.put("status","GROUND_IMPACT_MATRIX");report.put("ground_impact_passed",true);report.put("c4_complete",false);report.put("cannon_launch_in_this_run",false);
                restore(server);stage=2;return;
            }
            if(stage==2){
                if(!ready||player.position().distanceTo(original)>.05)return;
                if(view.requests().pending()!=0||dev.sifucraft.combat.FluidMotion.stats().active()!=0||PistonMachines.stats().pending()!=0)return;
                AtomicFiles.write(server.getWorldPath(LevelResource.ROOT).resolve(scenario.equals("matrix")?"p10-cannon-ground-impact.json":"p10-cannon-ground-survey.json"),out->new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report,out));
                LoggerFactory.getLogger("sifucraft").info("[cannon-ground-test] SURVEY recorded actual Native candidate; C4 blast matrix remains open {}",report);finished=true;
            }
        }catch(Exception|AssertionError error){LoggerFactory.getLogger("sifucraft").error("[cannon-ground-test] FAIL",error);restore(server);failure=new AssertionError(error);}
    }
    private void teleport(Vec3 point){player.teleportTo(player.level(),point.x,point.y,point.z,Set.of(),-90,0,true);player.setDeltaMovement(Vec3.ZERO);}
    void restore(MinecraftServer ignored){if(blast!=null){blast.close();blast=null;}if(player!=null&&original!=null){player.teleportTo(player.level(),original.x,original.y,original.z,Set.of(),yaw,pitch,true);player.setDeltaMovement(Vec3.ZERO);}}
}
