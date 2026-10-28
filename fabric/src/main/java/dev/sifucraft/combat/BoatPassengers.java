package dev.sifucraft.combat;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import dev.sifucraft.blocks.PistonMovePlans;
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

/** Actual native passengers, with one bounded lease and the vanilla boat's own seat transform. */
public final class BoatPassengers {
    private BoatPassengers() {}
    private static final long ACTION=ShapeProtocol.mapHash("sifucraft:boat_passenger");
    private static final ThreadLocal<EnemyProxy> seatQuery=new ThreadLocal<>();
    public static boolean positionRider(Entity passenger){return !(passenger instanceof EnemyProxy)||seatQuery.get()==passenger;}
    private static void evaluateSeat(Passenger p){
        var old=seatQuery.get();seatQuery.set(p.proxy);
        try{p.boat.positionRider(p.proxy);}finally{if(old==null)seatQuery.remove();else seatQuery.set(old);}
    }
    private enum Phase { ACQUIRE,SEATED,RELEASE }
    private static final class Passenger {
        final AbstractBoat boat;final EnemyProxy proxy;final EnemyFrame.Entry initial;final WorldMotionRequests.Scope scope;
        final AtomicBoolean queued=new AtomicBoolean();
        Phase phase=Phase.ACQUIRE;CompletableFuture<Reply> pending;Reply binding,last;
        long lease,deadline,renewAt;int operation;float facing,sentFacing;boolean closing,failed;
        SifuFrame.Vec3 wanted;float targetYaw;
        Passenger(AbstractBoat boat,EnemyProxy proxy,EnemyFrame.Entry initial,WorldMotionWorld.View view){
            this.boat=boat;this.proxy=proxy;this.initial=initial;scope=view.scope();facing=initial.yaw();
        }
    }
    private record Pose(WorldMotionRequests.Scope scope,Reply reply,long until){}
    private static final Map<EnemyFrame.Key,Passenger> passengers=new HashMap<>();
    private static final Map<EnemyFrame.Key,Pose> poses=new HashMap<>();
    private static final Map<EnemyFrame.Key,Long> retry=new HashMap<>();
    private static long boarded,steps,released,failed;
    private static int failureLogs;
    public record Stats(long boarded,long steps,long released,long failed,int active){}
    public static Stats stats(){return new Stats(boarded,steps,released,failed,passengers.size());}
    public static boolean holds(EnemyFrame.Key key){return passengers.containsKey(key);}

    private static boolean valid(WorldMotionWorld.View v,ServerLevel level){
        long now=WorldWriter.clockMillis();
        return WorldBlocks.ENABLED&&v!=null&&v.scope().equals(v.requests().scope())&&v.scope().save()==PistonMovePlans.saveKey(level)&&
            v.scene()!=null&&v.scene().matchesScope(v.scope().nativeSession(),v.scope().world())&&v.placement().equals(v.scene().placement())&&
            v.enemies()!=null&&v.enemies().session()==v.scope().nativeSession()&&v.enemies().worldGeneration()==v.scope().world()&&
            System.currentTimeMillis()-v.enemies().receivedMs()>=0&&System.currentTimeMillis()-v.enemies().receivedMs()<=200&&
            v.nativeStatus()!=null&&v.nativeStatus().flags()==0&&(v.nativeStatus().capabilities()&PASSENGER_CAPABILITY)!=0&&
            v.nativeStatus().atMs()<=now&&now-v.nativeStatus().atMs()<=200;
    }
    public static boolean candidate(AbstractBoat boat,EnemyProxy proxy){
        if(!(boat.level() instanceof ServerLevel level)||proxy.level()!=level||boat.isRemoved()||proxy.isRemoved()||!proxy.isAlive()||
            proxy.isPassenger()||proxy.isVehicle()||!boat.hasEnoughSpaceFor(proxy)||holds(proxy.sifuKey())||
            WorldWriter.clockMillis()<retry.getOrDefault(proxy.sifuKey(),0L)||
            !boat.getBoundingBox().inflate(.2,-.01,.2).intersects(proxy.getBoundingBox())||
            EnemyMirrors.hitAttacker(proxy.sifuKey().id(),proxy.sifuKey().spawn())!=proxy)return false;
        var v=WorldMotionWorld.current();if(!valid(v,level)||!FluidMotion.currentGeometry(v)||v.requests().pending()>=20)return false;
        var from=proxy.getEyePosition();var to=boat.getBoundingBox().getCenter();
        return !SceneExplosions.blocked(v.scene(),from,to)&&level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,proxy)).getType()==HitResult.Type.MISS;
    }
    /** Only adds native proxies to this boat's vanilla broadphase; free proxy pushing stays disabled. */
    public static List<Entity> candidates(AbstractBoat boat,AABB box,List<Entity> vanilla){
        if(!(boat.level() instanceof ServerLevel))return vanilla;
        var result=new ArrayList<>(vanilla);
        for(var entity:boat.level().getEntities(boat,box,e->e instanceof EnemyProxy))
            if(entity instanceof EnemyProxy proxy&&candidate(boat,proxy))result.add(proxy);
        return result;
    }
    /** The original boat already checked capacity, size, tags and passenger status. */
    public static boolean board(AbstractBoat boat,EnemyProxy proxy){
        if(!candidate(boat,proxy)||passengers.size()>=16)return false;
        var v=WorldMotionWorld.current();if(FluidMotion.yieldToMachine(proxy.sifuKey(),v))return false;
        var e=v.enemies().entries().stream().filter(entry->entry.key().equals(proxy.sifuKey())&&entry.alive()).findFirst().orElse(null);
        if(e==null)return false;
        var p=new Passenger(boat,proxy,e,v);passengers.put(e.key(),p);
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
        var uuid=p.boat.getUUID();var pos=p.boat.blockPosition();
        p.pending=v.requests().request(p.scope,id->{if(op==RESERVE)p.lease=id;
            return new Command(op,PASSENGER,id,op==RESERVE?0:p.lease,now,uuid.getMostSignificantBits(),uuid.getLeastSignificantBits(),ACTION,p.boat.level().getGameTime(),
                p.scope.save(),p.scope.map(),v.nativeStatus().shapeRevision(),p.scope.world(),p.initial.id(),p.initial.spawn(),op==RESERVE||op==RENEW?500:0,
                position,delta,p.initial.health(),p.initial.radius(),p.initial.halfHeight(),0,pos.getX(),pos.getY(),pos.getZ(),0,0,0,0,0,
                p.binding==null?0:p.binding.actorSerial(),p.binding==null?0:p.binding.componentToken(),facing);
        });
        p.pending.whenComplete((reply,error)->wake(p));
    }
    private static void mirror(Passenger p,WorldMotionWorld.View view,Reply r){
        poses.put(p.initial.key(),new Pose(p.scope,r,WorldWriter.clockMillis()+600));
        p.proxy.setPos(view.placement().mcX(r.position().x()),view.placement().mcY(r.position().z()-p.initial.halfHeight()),view.placement().mcZ(r.position().y()));
        p.proxy.setYRot(p.facing-90);p.proxy.setYHeadRot(p.facing-90);p.proxy.setYBodyRot(p.facing-90);
    }
    private static void retire(Passenger p){
        p.proxy.stopRiding();passengers.remove(p.initial.key());poses.remove(p.initial.key());retry.put(p.initial.key(),WorldWriter.clockMillis()+500);
        if(p.failed)failed++;else released++;
    }
    private static void close(WorldMotionWorld.View v,Passenger p,boolean failed){
        if(failed&&!p.closing&&failureLogs++<32){
            var reply=p.pending==null||!p.pending.isDone()||p.pending.isCompletedExceptionally()?null:p.pending.getNow(null);
            org.slf4j.LoggerFactory.getLogger("sifucraft").info("[boat-passenger] releasing target={} phase={} op={} receipt={} boat_removed={} proxy_removed={} vehicle={} pending={} deadline={}",
                    p.initial.key(),p.phase,p.operation,reply==null?-1:reply.status(),p.boat.isRemoved(),p.proxy.isRemoved(),p.proxy.getVehicle()==p.boat,v==null?-1:v.requests().pending(),p.deadline);
        }
        p.closing=true;p.failed|=failed;p.proxy.stopRiding();
        if(p.pending!=null&&!p.pending.isDone())return;
        if(p.binding==null||v==null||v.nativeStatus()==null||!p.scope.equals(v.scope())||!p.scope.equals(v.requests().scope())){
            retire(p);return; // native lease still has its independent <=500ms deadline
        }
        p.phase=Phase.RELEASE;
        send(v,p,CANCEL,p.last.position(),SifuFrame.Vec3.ZERO,0);
    }
    private static void advance(Passenger p,boolean simulate){
        var v=WorldMotionWorld.current();long now=WorldWriter.clockMillis();
        if(p.phase==Phase.RELEASE){
            if(p.pending!=null&&!p.pending.isDone()&&now<=p.deadline+1000)return;
            var r=p.pending==null||p.pending.isCompletedExceptionally()?null:p.pending.getNow(null);
            if(r!=null&&p.binding!=null&&r.actorSerial()==p.binding.actorSerial()&&r.componentToken()==p.binding.componentToken()&&r.restored()==7&&
                    (r.status()==3||r.status()==6)&&v!=null&&p.scope.equals(v.scope())){
                mirror(p,v,r);FluidMotion.passengerReleased(p.proxy,p.scope,p.boat.getUUID(),p.binding,r,p.proxy.getDeltaMovement());
            }else p.failed=true;
            retire(p);return;
        }
        if(now>p.deadline+1000){p.failed=true;retire(p);return;}
        if(v==null||!p.scope.equals(v.scope())||p.proxy.isRemoved()||!p.proxy.isAlive()||
            EnemyMirrors.hitAttacker(p.initial.id(),p.initial.spawn())!=p.proxy){close(v,p,true);return;}
        if(p.boat.isRemoved()){close(v,p,false);return;}
        if(p.pending!=null){
            if(!p.pending.isDone())return;
            var r=p.pending.isCompletedExceptionally()?null:p.pending.getNow(null);p.pending=null;
            boolean identity=r!=null&&(p.binding==null||r.actorSerial()==p.binding.actorSerial()&&r.componentToken()==p.binding.componentToken());
            if(!identity||p.operation==RESERVE&&r.status()!=1||p.operation!=RESERVE&&r.status()!=2){close(v,p,true);return;}
            p.last=r;
            if(p.operation==RESERVE){
                p.binding=r;
                if(p.closing||!candidateAfterReservation(p)||!p.proxy.startRiding(p.boat)){close(v,p,true);return;}
                p.phase=Phase.SEATED;boarded++;
            }else if(p.operation==STEP){p.facing=p.sentFacing;steps++;}
            mirror(p,v,r);
        }
        if(p.closing){close(v,p,p.failed);return;}
        if(p.phase!=Phase.SEATED)return;
        if(p.proxy.getVehicle()!=p.boat){close(v,p,false);return;}
        if(!valid(v,(ServerLevel)p.proxy.level())){if(now>=p.deadline)close(v,p,true);return;}
        if(now>=p.renewAt){send(v,p,RENEW,p.last.position(),SifuFrame.Vec3.ZERO,0);return;}
        if(!FluidMotion.currentGeometry(v))return;
        // Use vanilla's actual attachment and yaw constraint, then restore the actual hit mirror.
        if(simulate){
            evaluateSeat(p);var seat=p.proxy.position();p.targetYaw=Mth.wrapDegrees(p.proxy.getYRot()+90);
            var offset=p.binding.rootOffset();var placement=v.placement();
            p.wanted=new SifuFrame.Vec3((float)placement.ueX(seat.x)-offset.x(),(float)placement.ueY(seat.z)-offset.y(),(float)placement.ueZ(seat.y)+p.binding.rootHalfHeight()-offset.z());
            mirror(p,v,p.last);
        }
        if(p.wanted==null)return;
        var wanted=p.wanted;var at=p.last.position();var delta=new Vec3(wanted.x()-at.x(),wanted.y()-at.y(),wanted.z()-at.z());
        float turn=Mth.wrapDegrees(p.targetYaw-p.facing);float facing=Mth.wrapDegrees(p.facing+Math.clamp(turn,-15,15));
        if(!Double.isFinite(delta.lengthSqr())||delta.length()>300){close(v,p,true);return;}
        if(delta.length()<.01&&Math.abs(turn)<.01)return;
        if(v.requests().pending()>=24)return;
        var step=delta.length()>9.9?delta.normalize().scale(9.9):delta;
        send(v,p,STEP,at,new SifuFrame.Vec3((float)step.x,(float)step.y,(float)step.z),facing);
    }
    private static boolean candidateAfterReservation(Passenger p){
        return !p.boat.isRemoved()&&!p.proxy.isPassenger()&&p.boat.hasEnoughSpaceFor(p.proxy)&&
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
        passengers.clear();poses.clear();retry.clear();
    }
}
