package dev.sifucraft.combat;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import dev.sifucraft.blocks.PistonMovePlans;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.bridge.WorldWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Actual native passengers of a boat or minecart, with one bounded lease and the vehicle's own vanilla seat transform. */
public final class BoatPassengers {
    private BoatPassengers() {}
    private static final long ACTION=ShapeProtocol.mapHash("sifucraft:boat_passenger");
    private static final ThreadLocal<EnemyProxy> seatQuery=new ThreadLocal<>();
    /** A minecart has one seat, already checked by vanilla through isVehicle. */
    private static boolean fits(Entity vehicle,EnemyProxy proxy){return vehicle instanceof AbstractBoat boat?boat.hasEnoughSpaceFor(proxy):!vehicle.isVehicle();}
    public static boolean positionRider(Entity passenger){return !(passenger instanceof EnemyProxy)||seatQuery.get()==passenger;}
    private static void evaluateSeat(Passenger p){
        var old=seatQuery.get();seatQuery.set(p.proxy);
        try{p.boat.positionRider(p.proxy);}finally{if(old==null)seatQuery.remove();else seatQuery.set(old);}
    }
    private enum Phase { ACQUIRE,SEATED,RELEASE,MACHINE }
    private record PendingStep(CompletableFuture<Reply> result,float facing){}
    private static final class Passenger {
        final Entity boat;final EnemyProxy proxy;final EnemyFrame.Entry initial;final WorldMotionRequests.Scope scope;
        final AtomicBoolean queued=new AtomicBoolean();
        Phase phase=Phase.ACQUIRE;CompletableFuture<Reply> pending;Reply binding,last;
        List<PendingStep> batch;
        boolean machine,resuming,machineFinished,machinePaused;long resumeAfter,machineUntil;
        boolean effectHandoff,effectConfirmed;int effectSerial;float health;
        CompletableFuture<Reply> effect;
        long lease,deadline,renewAt;int operation;float facing,sentFacing;boolean closing,failed,obstructed;
        long releaseUntil;boolean releasePaused;
        SifuFrame.Vec3 wanted;float targetYaw;
        Passenger(Entity boat,EnemyProxy proxy,EnemyFrame.Entry initial,WorldMotionWorld.View view){
            this.boat=boat;this.proxy=proxy;this.initial=initial;scope=view.scope();facing=initial.yaw();health=initial.health();
        }
    }
    private record Pose(WorldMotionRequests.Scope scope,Reply reply,long until){}
    private static final Map<EnemyFrame.Key,Passenger> passengers=new HashMap<>();
    private static final Map<EnemyFrame.Key,Pose> poses=new HashMap<>();
    private static final Map<EnemyFrame.Key,Long> retry=new HashMap<>();
    private static volatile MinecraftServer activeServer;
    private static final AtomicBoolean maintenance=new AtomicBoolean();
    private static long boarded,steps,released,failed;
    private static int failureLogs;
    public record Stats(long boarded,long steps,long released,long failed,int active){}
    public static Stats stats(){return new Stats(boarded,steps,released,failed,passengers.size());}
    public static boolean holds(EnemyFrame.Key key){return passengers.containsKey(key);}
    public static boolean seated(EnemyFrame.Key key){var p=passengers.get(key);return p!=null&&p.phase==Phase.SEATED&&!p.machine;}
    /** Distance from the last actual reserved root to the latest vanilla seat target. */
    public static double seatError(EnemyFrame.Key key){
        var p=passengers.get(key);if(p==null||p.wanted==null||p.last==null||p.phase!=Phase.SEATED)return Double.POSITIVE_INFINITY;
        var at=p.last.position();var target=p.wanted;return Math.sqrt(Math.pow(at.x()-target.x(),2)+Math.pow(at.y()-target.y(),2)+Math.pow(at.z()-target.z(),2))/100;
    }
    /** Server-thread diagnostics use the actual reserved root, not the visual proxy dimensions. */
    public static AABB physicalBounds(EnemyFrame.Key key,WorldMotionWorld.View view){
        var p=passengers.get(key);if(p==null||p.binding==null||p.last==null||!p.scope.equals(view.scope()))return null;
        var root=p.binding.rootOffset();var at=p.last.position();double radius=p.binding.rootRadius()/100.0,half=p.binding.rootHalfHeight()/100.0;
        double x=view.placement().mcX(at.x()+root.x()),y=view.placement().mcY(at.z()+root.z()),z=view.placement().mcZ(at.y()+root.y());
        return new AABB(x-radius,y-half,z-radius,x+radius,y+half,z+radius);
    }
    public static String diagnostic(EnemyFrame.Key key){
        var p=passengers.get(key);if(p==null)return "absent";
        var r=p.pending!=null&&p.pending.isDone()&&!p.pending.isCompletedExceptionally()?p.pending.getNow(null):null;
        return "phase="+p.phase+",machine="+p.machine+",finished="+p.machineFinished+",lease="+p.lease+",op="+p.operation+
            ",receipt="+(r==null?null:r.status())+",position="+(p.last==null?null:p.last.position())+",deadline="+p.deadline+",machineUntil="+p.machineUntil;
    }
    /** Release the native lease before the machine acquires it; retain only the MC seat. */
    public static boolean yieldToMachine(EnemyFrame.Key key,WorldMotionWorld.View view){
        var p=passengers.get(key);if(p==null||!p.scope.equals(view.scope()))return false;
        if(p.phase==Phase.MACHINE)return false;
        if(p.machine)return true;
        p.machine=true;p.machineFinished=false;p.machineUntil=WorldWriter.clockMillis()+2000;
        close(view,p,false);return true;
    }
    public static void machineFinished(EnemyFrame.Key key,WorldMotionRequests.Scope scope){
        var p=passengers.get(key);if(p!=null&&p.scope.equals(scope)&&p.phase==Phase.MACHINE){p.machineFinished=true;p.resumeAfter=WorldWriter.clockMillis();}
    }
    /** DAMAGE is queued first, so Native records this exact passenger source
     * before its cancellation. Retain only the vanilla seat while H7 recovers. */
    public static void damageSubmitted(EnemyProxy proxy,WorldMotionWorld.View view,CompletableFuture<Reply> result){
        var p=passengers.get(proxy.sifuKey());
        if(p==null||p.binding==null||!p.scope.equals(view.scope())||p.boat.isRemoved()||proxy.getVehicle()!=p.boat)return;
        p.effectHandoff=true;p.effectConfirmed=false;p.effect=result;p.effectSerial=p.binding.actorSerial();
        p.machine=true;p.machineFinished=true;p.machineUntil=WorldWriter.clockMillis()+12000;
        close(view,p,false);
    }
    /** Server task also runs while an actual Native pause freezes world ticks. */
    public static void machinePaused(WorldMotionWorld.View view){
        long now=WorldWriter.clockMillis();if(view==null||view.nativeStatus()==null||view.nativeStatus().atMs()>now||now-view.nativeStatus().atMs()>200||(view.nativeStatus().flags()&PAUSED)==0)return;
        for(var p:passengers.values())if(p.phase==Phase.MACHINE&&p.scope.equals(view.scope()))p.machinePaused=true;
    }
    private static boolean paused(WorldMotionWorld.View view,long now){return view!=null&&view.nativeStatus()!=null&&view.nativeStatus().atMs()<=now&&now-view.nativeStatus().atMs()<=200&&(view.nativeStatus().flags()&PAUSED)!=0;}
    /** Paused integrated servers still execute tasks; handback observation must
     * not depend on the next world/entity tick. */
    public static void offered(){
        var server=activeServer;if(server==null||!maintenance.compareAndSet(false,true))return;
        server.execute(()->{
            maintenance.set(false);var view=WorldMotionWorld.current();long now=WorldWriter.clockMillis();machinePaused(view);
            for(var p:new ArrayList<>(passengers.values()))if(p.phase==Phase.RELEASE||p.phase==Phase.MACHINE||paused(view,now))advance(p,false);
            if(passengers.isEmpty())activeServer=null;
        });
    }

    /** A boat's low hull cannot stand in for its upright native passengers at walls/ceilings. */
    public static Vec3 constrainBoat(AbstractBoat boat,Vec3 movement){
        if(boat.level().dimension()!=net.minecraft.world.level.Level.OVERWORLD)return movement;
        var result=movement;
        for(var rider:boat.getPassengers())if(rider instanceof EnemyProxy proxy&&proxy.isAlive()){
            result=Entity.collideBoundingBox(boat,result,proxy.getBoundingBox(),boat.level(),java.util.List.of());
        }
        return result;
    }

    /** Vanilla's water fallback is the boat centre. A native capsule cannot
     * overlap the remaining passenger there while its seat order changes. */
    public static Vec3 dismount(AbstractBoat boat,net.minecraft.world.entity.LivingEntity rider,Vec3 vanilla){
        var natives=boat.getPassengers().stream().filter(e->e instanceof EnemyProxy&&e!=rider&&e.isAlive()).toList();
        if(natives.isEmpty())return vanilla;
        var occupied=boat.getBoundingBox();for(var e:natives)occupied=occupied.minmax(e.getBoundingBox());
        var proposed=rider.getBoundingBox().move(vanilla.subtract(rider.position()));
        if(!proposed.intersects(occupied))return vanilla;
        double distance=boat.getBbWidth()+rider.getBbWidth();
        double yaw=Math.toRadians(rider.getYRot());
        for(int i=0;i<8;i++){
            double angle=yaw+i*Math.PI/4;
            var candidate=new Vec3(boat.getX()-Math.sin(angle)*distance,vanilla.y,boat.getZ()+Math.cos(angle)*distance);
            var box=rider.getBoundingBox().move(candidate.subtract(rider.position()));
            if(!box.intersects(occupied)&&SceneFluidGeometry.knownVolume(boat.level(),box)&&
                net.minecraft.world.entity.vehicle.DismountHelper.canDismountTo(boat.level(),rider,box))return candidate;
        }
        return vanilla;
    }

    private static boolean valid(WorldMotionWorld.View v,ServerLevel level){return valid(v,level,true);}
    private static boolean valid(WorldMotionWorld.View v,ServerLevel level,boolean acquiring){
        long now=WorldWriter.clockMillis();
        return WorldBlocks.ENABLED&&v!=null&&v.scope().equals(v.requests().scope())&&v.scope().save()==PistonMovePlans.saveKey(level)&&
            v.scene()!=null&&v.scene().matchesScope(v.scope().nativeSession(),v.scope().world())&&v.placement().equals(v.scene().placement())&&
            v.enemies()!=null&&v.enemies().session()==v.scope().nativeSession()&&v.enemies().worldGeneration()==v.scope().world()&&
            System.currentTimeMillis()-v.enemies().receivedMs()>=0&&System.currentTimeMillis()-v.enemies().receivedMs()<=200&&
            v.nativeStatus()!=null&&v.nativeStatus().flags()==0&&(!acquiring||(v.nativeStatus().capabilities()&PASSENGER_CAPABILITY)!=0)&&
            v.nativeStatus().atMs()<=now&&now-v.nativeStatus().atMs()<=200;
    }
    public static boolean candidate(Entity boat,EnemyProxy proxy){
        if(!(boat.level() instanceof ServerLevel level)||proxy.level()!=level||boat.isRemoved()||proxy.isRemoved()||!proxy.isAlive()||
            proxy.isPassenger()||proxy.isVehicle()||!fits(boat,proxy)||holds(proxy.sifuKey())||
            WorldWriter.clockMillis()<retry.getOrDefault(proxy.sifuKey(),0L)||
            !boat.getBoundingBox().inflate(.2,-.01,.2).intersects(proxy.getBoundingBox())||
            EnemyMirrors.hitAttacker(proxy.sifuKey().id(),proxy.sifuKey().spawn())!=proxy)return false;
        var v=WorldMotionWorld.current();if(!valid(v,level)||!FluidMotion.currentGeometry(v)||v.requests().pending()>=20)return false;
        var from=proxy.getEyePosition();var to=boat.getBoundingBox().getCenter();
        return !SceneExplosions.blocked(v.scene(),from,to)&&level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,proxy)).getType()==HitResult.Type.MISS;
    }
    /** Only adds native proxies to this boat's vanilla broadphase; free proxy pushing stays disabled. */
    public static List<Entity> candidates(Entity boat,AABB box,List<Entity> vanilla){
        if(!(boat.level() instanceof ServerLevel))return vanilla;
        var result=new ArrayList<>(vanilla);
        for(var entity:boat.level().getEntities(boat,box,e->e instanceof EnemyProxy))
            if(entity instanceof EnemyProxy proxy&&candidate(boat,proxy))result.add(proxy);
        return result;
    }
    /** The original boat already checked capacity, size, tags and passenger status. */
    public static boolean board(Entity boat,EnemyProxy proxy){
        if(!candidate(boat,proxy)||passengers.size()>=16)return false;
        var v=WorldMotionWorld.current();if(FluidMotion.yieldToMachine(proxy.sifuKey(),v))return false;
        var e=v.enemies().entries().stream().filter(entry->entry.key().equals(proxy.sifuKey())&&entry.alive()).findFirst().orElse(null);
        if(e==null)return false;
        var p=new Passenger(boat,proxy,e,v);passengers.put(e.key(),p);
        activeServer=boat.level().getServer();
        send(v,p,RESERVE,e.location(),SifuFrame.Vec3.ZERO,0);
        return false; // relationship is committed only after Native Prepared
    }
    private static void wake(Passenger p){
        if(!p.queued.compareAndSet(false,true))return;
        p.proxy.level().getServer().execute(()->{p.queued.set(false);if(passengers.get(p.initial.key())==p)advance(p,false);});
    }
    private static void send(WorldMotionWorld.View v,Passenger p,int op,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,float facing){
        long now=WorldWriter.clockMillis();p.operation=op;p.sentFacing=facing;
        if(op==RESERVE||op==RENEW){p.deadline=now+500;p.renewAt=now+200;}
        p.pending=request(v,p,op,position,delta,facing,now);
        p.pending.whenComplete((reply,error)->wake(p));
    }
    private static CompletableFuture<Reply> request(WorldMotionWorld.View v,Passenger p,int op,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,float facing,long now){
        var uuid=p.boat.getUUID();var pos=p.boat.blockPosition();
        return v.requests().request(p.scope,id->{if(op==RESERVE)p.lease=id;
            return new Command(op,PASSENGER,id,op==RESERVE?0:p.lease,now,uuid.getMostSignificantBits(),uuid.getLeastSignificantBits(),ACTION,p.boat.level().getGameTime(),
                p.scope.save(),p.scope.map(),v.nativeStatus().shapeRevision(),p.scope.world(),p.initial.id(),p.initial.spawn(),op==RESERVE||op==RENEW?500:0,
                position,delta,p.health,p.initial.radius(),p.initial.halfHeight(),0,pos.getX(),pos.getY(),pos.getZ(),0,0,0,0,0,
                p.binding==null?0:p.binding.actorSerial(),p.binding==null?0:p.binding.componentToken(),facing);
        });
    }
    private static void sendSteps(WorldMotionWorld.View v,Passenger p,Vec3 delta,float turn){
        int total=Math.max(1,(int)Math.ceil(delta.length()/9.9));
        int count=Math.min(Math.min(8,total),24-v.requests().pending());if(count<=0)return;
        var part=delta.scale(1.0/total);var at=p.last.position();long now=WorldWriter.clockMillis();
        var batch=new ArrayList<PendingStep>(count);p.operation=STEP;
        for(int i=0;i<count;i++){
            float facing=Mth.wrapDegrees(p.facing+Math.clamp(turn,-15f*(i+1),15f*(i+1)));
            var position=new SifuFrame.Vec3((float)(at.x()+part.x*i),(float)(at.y()+part.y*i),(float)(at.z()+part.z*i));
            var movement=new SifuFrame.Vec3((float)part.x,(float)part.y,(float)part.z);
            batch.add(new PendingStep(request(v,p,STEP,position,movement,facing,now),facing));
        }
        p.batch=List.copyOf(batch);
        p.pending=CompletableFuture.allOf(batch.stream().map(PendingStep::result).toArray(CompletableFuture[]::new))
            .thenApply(ignored->batch.getLast().result.getNow(null));
        p.pending.whenComplete((reply,error)->wake(p));
    }
    private static boolean unexecuted(Passenger p,Reply r){return r!=null&&p.binding!=null&&r.actorSerial()==p.binding.actorSerial()&&r.componentToken()==p.binding.componentToken()&&
        (r.status()==8||r.status()==9)&&r.flags()==STEP_NOT_EXECUTED&&r.restored()==0&&r.health()==p.health;}
    private static void mirror(Passenger p,WorldMotionWorld.View view,Reply r){
        poses.put(p.initial.key(),new Pose(p.scope,r,WorldWriter.clockMillis()+600));
        p.proxy.setPos(view.placement().mcX(r.position().x()),view.placement().mcY(r.position().z()-p.initial.halfHeight()),view.placement().mcZ(r.position().y()));
        p.proxy.setYRot(p.facing-90);p.proxy.setYHeadRot(p.facing-90);p.proxy.setYBodyRot(p.facing-90);
    }
    private static void retire(Passenger p){
        p.proxy.stopRiding();passengers.remove(p.initial.key());poses.remove(p.initial.key());retry.put(p.initial.key(),WorldWriter.clockMillis()+500);
        if(p.failed)failed++;else if(p.phase!=Phase.MACHINE)released++;
    }
    private static void close(WorldMotionWorld.View v,Passenger p,boolean failed){
        if(failed&&!p.closing&&failureLogs++<32){
            var reply=p.pending==null||!p.pending.isDone()||p.pending.isCompletedExceptionally()?null:p.pending.getNow(null);
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-passenger] releasing target={} phase={} op={} receipt={} boat_removed={} proxy_removed={} vehicle={} pending={} deadline={}",
                    p.initial.key(),p.phase,p.operation,reply==null?-1:reply.status(),p.boat.isRemoved(),p.proxy.isRemoved(),p.proxy.getVehicle()==p.boat,v==null?-1:v.requests().pending(),p.deadline);
        }
        p.closing=true;p.failed|=failed;if(failed)p.machine=false;if(!p.machine)p.proxy.stopRiding();
        if(p.pending!=null&&!p.pending.isDone())return;
        if(p.binding==null||v==null||v.nativeStatus()==null||!p.scope.equals(v.scope())||!p.scope.equals(v.requests().scope())){
            retire(p);return; // native lease still has its independent <=500ms deadline
        }
        p.phase=Phase.RELEASE;
        p.releaseUntil=WorldWriter.clockMillis()+1500;
        send(v,p,CANCEL,p.last.position(),SifuFrame.Vec3.ZERO,0);
    }
    private static void advance(Passenger p,boolean simulate){
        var v=WorldMotionWorld.current();long now=WorldWriter.clockMillis();
        boolean paused=paused(v,now);
        if(paused&&p.binding!=null&&p.phase!=Phase.RELEASE&&p.phase!=Phase.MACHINE){
            p.machine=true;p.machineFinished=true;p.machinePaused=true;p.machineUntil=now+2000;close(v,p,false);
        }
        if(p.phase==Phase.RELEASE){
            if(paused){p.releasePaused=true;p.machinePaused=true;}
            else if(p.releasePaused){p.releasePaused=false;p.releaseUntil=now+1500;}
            if(p.pending!=null&&!p.pending.isDone()&&(paused||now<=p.releaseUntil))return;
            var r=p.pending==null||p.pending.isCompletedExceptionally()?null:p.pending.getNow(null);
            if(r!=null&&p.binding!=null&&r.actorSerial()==p.binding.actorSerial()&&r.componentToken()==p.binding.componentToken()&&r.restored()==7&&
                    (r.status()==3||r.status()==6||r.status()==13)&&v!=null&&p.scope.equals(v.scope())){
                mirror(p,v,r);
                if(p.machine&&!p.failed&&p.proxy.getVehicle()==p.boat&&!p.boat.isRemoved()){
                    p.phase=Phase.MACHINE;p.closing=false;p.binding=null;p.last=r;p.pending=null;p.batch=null;p.resumeAfter=now+100;released++;return;
                }
                FluidMotion.passengerReleased(p.proxy,p.scope,p.boat.getUUID(),p.binding,r,p.proxy.getDeltaMovement());
            }else p.failed=true;
            retire(p);return;
        }
        if(p.phase==Phase.MACHINE){
            if(v==null||!p.scope.equals(v.scope())||p.proxy.isRemoved()||p.boat.isRemoved()||p.proxy.getVehicle()!=p.boat){retire(p);return;}
            if(v.nativeStatus()!=null&&v.nativeStatus().atMs()<=now&&now-v.nativeStatus().atMs()<=200&&(v.nativeStatus().flags()&PAUSED)!=0){p.machinePaused=true;return;}
            // This is a Native-free MC seat wait, not a movement lease. A true
            // pause cannot consume its post-resume recovery window.
            if(p.machinePaused){p.machinePaused=false;p.machineUntil=now+(p.effectHandoff?12000:2000);}
            if(now>=p.machineUntil){p.failed=true;retire(p);return;}
            if(p.effectHandoff&&!p.effectConfirmed){
                if(p.effect==null||!p.effect.isDone())return;
                var damage=p.effect.isCompletedExceptionally()?null:p.effect.getNow(null);
                if(damage==null||(damage.status()!=2&&damage.status()!=4)||damage.actorSerial()!=p.effectSerial||damage.health()<=0){p.failed=true;retire(p);return;}
                p.health=damage.health();p.effectConfirmed=true;p.effect=null;
            }
            if(now<p.resumeAfter||!p.machineFinished&&dev.sifucraft.blocks.PistonMachines.machineActive(p.initial.key())||!valid(v,(ServerLevel)p.proxy.level())||!FluidMotion.currentGeometry(v))return;
            var e=v.enemies().entries().stream().filter(x->x.key().equals(p.initial.key())&&x.alive()&&x.health()==p.health).findFirst().orElse(null);
            if(e==null){if(p.effectHandoff)return;p.failed=true;retire(p);return;}
            p.phase=Phase.ACQUIRE;p.resuming=true;send(v,p,RESERVE,e.location(),SifuFrame.Vec3.ZERO,0);return;
        }
        if(now>p.deadline+1000){p.failed=true;retire(p);return;}
        if(v==null||!p.scope.equals(v.scope())||p.proxy.isRemoved()||!p.proxy.isAlive()||
            EnemyMirrors.hitAttacker(p.initial.id(),p.initial.spawn())!=p.proxy){close(v,p,true);return;}
        if(p.boat.isRemoved()){close(v,p,false);return;}
        if(p.effectHandoff&&p.closing){
            if(p.pending!=null&&!p.pending.isDone())return;
            p.pending=null;p.batch=null;close(v,p,false);return;
        }
        if(p.pending!=null){
            if(!p.pending.isDone())return;
            var r=p.pending.isCompletedExceptionally()?null:p.pending.getNow(null);p.pending=null;
            int executed=1;
            if(p.batch!=null){
                executed=0;Reply actual=null;boolean refused=false;
                for(var step:p.batch){
                    var receipt=step.result.isCompletedExceptionally()?null:step.result.getNow(null);
                    if(unexecuted(p,receipt))continue;
                    boolean current=receipt!=null&&receipt.actorSerial()==p.binding.actorSerial()&&receipt.componentToken()==p.binding.componentToken()&&receipt.restored()==0&&receipt.health()==p.health;
                    if(!current||receipt.status()!=2&&receipt.status()!=4&&receipt.status()!=5){actual=receipt;refused=true;break;}
                    actual=receipt;p.sentFacing=step.facing;executed++;
                }
                if(refused||actual!=null)r=actual;
                p.batch=null;
                if(refused){
                    org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-passenger] batch refused target={} receipt={} status={}",p.initial.key(),r==null?0:r.id(),r==null?-1:r.status());
                    close(v,p,true);return;
                }
            }
            boolean identity=r!=null&&(p.binding==null||r.actorSerial()==p.binding.actorSerial()&&r.componentToken()==p.binding.componentToken());
            if(p.resuming&&p.operation==RESERVE&&r!=null&&r.status()==9){p.phase=Phase.MACHINE;p.resumeAfter=now+50;return;}
            if(p.resuming&&p.effectHandoff&&p.operation==RESERVE&&r!=null&&(r.status()==8||r.status()==12)&&r.actorSerial()==0&&r.componentToken()==0){
                p.phase=Phase.MACHINE;p.resumeAfter=now+50;return;
            }
            boolean unexecuted=p.operation==STEP&&unexecuted(p,r);
            if(!unexecuted){
                boolean contact=identity&&p.operation==STEP&&(r.status()==4||r.status()==5)&&r.restored()==0&&r.health()==p.health;
                if(!identity||p.operation==RESERVE&&r.status()!=1||p.operation!=RESERVE&&r.status()!=2&&!contact){
                    org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-passenger] receipt refused op={} id={} status={} flags={} serial={} token={} old_serial={} old_token={} native_age={} broker_failure={}",p.operation,r==null?0:r.id(),r==null?-1:r.status(),r==null?0:r.flags(),r==null?0:r.actorSerial(),r==null?0:r.componentToken(),p.binding==null?0:p.binding.actorSerial(),p.binding==null?0:p.binding.componentToken(),v==null||v.nativeStatus()==null?-1:now-v.nativeStatus().atMs(),v==null?"":v.requests().lastFailure());
                    close(v,p,true);return;
                }
                p.last=r;
                if(p.operation==RESERVE){
                    p.binding=r;
                    boolean linked=p.resuming?p.proxy.getVehicle()==p.boat&&!p.boat.isRemoved():candidateAfterReservation(p)&&p.proxy.startRiding(p.boat);
                    if(p.closing||!linked){close(v,p,true);return;}
                    p.phase=Phase.SEATED;p.machine=p.resuming=p.machineFinished=p.effectHandoff=p.effectConfirmed=false;p.effect=null;p.wanted=null;boarded++;
                }else if(p.operation==STEP){p.facing=p.sentFacing;steps+=executed;p.obstructed=contact;}
                mirror(p,v,r);
            } // A proven unexecuted STEP keeps the last actual pose; retry under the current geometry.
        }
        if(p.closing){close(v,p,p.failed);return;}
        if(p.phase!=Phase.SEATED)return;
        if(p.proxy.getVehicle()!=p.boat){close(v,p,false);return;}
        if(!valid(v,(ServerLevel)p.proxy.level(),false)){if(now>=p.deadline)close(v,p,true);return;}
        if(now>=p.renewAt){send(v,p,RENEW,p.last.position(),SifuFrame.Vec3.ZERO,0);return;}
        if(!FluidMotion.currentGeometry(v))return;
        if(p.obstructed&&!simulate)return; // a blocked sweep waits for the next authoritative boat tick
        // Use vanilla's actual attachment and yaw constraint, then restore the actual hit mirror.
        if(simulate){
            p.obstructed=false;
            evaluateSeat(p);var seat=p.proxy.position();p.targetYaw=Mth.wrapDegrees(p.proxy.getYRot()+90);
            var offset=p.binding.rootOffset();var placement=v.placement();
            p.wanted=new SifuFrame.Vec3((float)placement.ueX(seat.x)-offset.x(),(float)placement.ueY(seat.z)-offset.y(),(float)placement.ueZ(seat.y)+p.binding.rootHalfHeight()-offset.z());
            mirror(p,v,p.last);
        }
        if(p.wanted==null)return;
        var wanted=p.wanted;var at=p.last.position();var delta=new Vec3(wanted.x()-at.x(),wanted.y()-at.y(),wanted.z()-at.z());
        float turn=Mth.wrapDegrees(p.targetYaw-p.facing);
        if(!Double.isFinite(delta.lengthSqr())||delta.length()>300){close(v,p,true);return;}
        if(delta.length()<.01&&Math.abs(turn)<.01)return;
        if(v.requests().pending()>=24)return;
        sendSteps(v,p,delta,turn);
    }
    private static boolean candidateAfterReservation(Passenger p){
        return !p.boat.isRemoved()&&!p.proxy.isPassenger()&&fits(p.boat,p.proxy)&&
                p.boat.getBoundingBox().inflate(.2,-.01,.2).intersects(p.proxy.getBoundingBox());
    }
    public static void tick(MinecraftServer server){
        for(var p:new ArrayList<>(passengers.values()))advance(p,true);
        retry.entrySet().removeIf(e->WorldWriter.clockMillis()>=e.getValue());
    }
    public static EnemyFrame.Entry pose(EnemyFrame frame,EnemyFrame.Entry e){
        var p=poses.get(e.key());if(p==null)return e;
        if(p.scope.nativeSession()!=frame.session()||p.scope.world()!=frame.worldGeneration()||WorldWriter.clockMillis()>p.until||frame.frame()>p.reply.nativeFrame()){poses.remove(e.key());return e;}
        return new EnemyFrame.Entry(e.id(),e.spawn(),p.reply.position(),e.yaw(),e.radius(),e.halfHeight(),e.health(),e.maxHealth(),e.flags(),e.faction(),e.velocity());
    }
    public static void stop(){
        var v=WorldMotionWorld.current();for(var p:new ArrayList<>(passengers.values()))close(v,p,false);
        passengers.clear();poses.clear();retry.clear();activeServer=null;maintenance.set(false);
    }
}
