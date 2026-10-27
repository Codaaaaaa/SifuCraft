package dev.sifucraft.combat;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.PistonMovePlans;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.bridge.WorldWriter;
import dev.sifucraft.collision.SifuCollision;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Original 20Hz fluid integration, actual native capsule sweeps, short renewed world leases. */
public final class FluidMotion {
    private FluidMotion(){}
    private static final long ACTION=ShapeProtocol.mapHash("sifucraft:fluid");
    private enum Phase { ACQUIRE,IDLE,MOVE,RENEW,RELEASE,CANCEL }
    private static final class Flight {
        final EnemyFrame.Key key;final EnemyFrame.Entry initial;final WorldMotionRequests.Scope scope;final ServerLevel level;final UUID source;
        final AtomicBoolean queued=new AtomicBoolean();
        final BlockPos sourcePos;final long stateHash,sectionRevision;
        Phase phase=Phase.ACQUIRE;CompletableFuture<Reply> pending;Reply binding,last;
        VanillaFluidBody body;long lease,deadline,renewAt,lastTick=Long.MIN_VALUE;Vec3 requested,actualStart,nextVelocity;int parts,part;
        int pendingOp,retryOp;SifuFrame.Vec3 pendingDelta=SifuFrame.Vec3.ZERO;
        boolean resumeMove;
        Flight(WorldMotionWorld.View view,ServerLevel level,EnemyFrame.Entry e,EnemyProxy proxy,BlockPos at){
            key=e.key();initial=e;scope=view.scope();this.level=level;source=proxy.getUUID();sourcePos=at;
            long hash=ShapeProtocol.mapHash(BlockStateKey.encode(level.getBlockState(at)));stateHash=hash==0?1:hash;
            var section=WorldBlocks.index().snapshot(SectionPos.of(at).asLong());sectionRevision=section==null?0:section.revision();
        }
    }
    private record Pose(WorldMotionRequests.Scope scope,Reply reply,long until){}
    private static final Map<EnemyFrame.Key,Flight> flights=new HashMap<>();
    private static final Map<EnemyFrame.Key,Long> retry=new HashMap<>();
    private static final Map<EnemyFrame.Key,Pose> poses=new HashMap<>();
    private static WorldMotionRequests.Scope scope;
    private static long acquired,steps,blocked,renewed,released,failed;private static int logs;
    public record Stats(long acquired,long steps,long blocked,long renewed,long released,long failed,int active){}
    public static Stats stats(){return new Stats(acquired,steps,blocked,renewed,released,failed,flights.size());}
    public record Sample(EnemyFrame.Key key,Vec3 position,Vec3 velocity,float width,float height,long seed,int entityId,long tick){}
    public interface Observer {void prepared(Sample initial);void simulated(Sample planned);void completed(Sample actual);}
    private static volatile Observer observer;
    public static void observe(Observer value){if(value!=null&&!Boolean.getBoolean("sifucraft.fluidMotionSelfTest"))throw new IllegalStateException("isolated fixture only");observer=value;}
    private static Sample sample(Flight f){return new Sample(f.key,f.body.position(),f.body.getDeltaMovement(),f.body.getBbWidth(),f.body.getBbHeight(),((long)f.key.id()<<32)^f.key.spawn(),f.body.getId(),f.lastTick);}
    private static boolean valid(WorldMotionWorld.View v,ServerLevel level){
        if(v==null||!WorldBlocks.ENABLED||!v.scope().equals(v.requests().scope())||v.scope().save()!=PistonMovePlans.saveKey(level)||v.scene()==null||
            !v.scene().matchesScope(v.scope().nativeSession(),v.scope().world())||!v.placement().equals(v.scene().placement())||v.enemies()==null||
            v.enemies().session()!=v.scope().nativeSession()||v.enemies().worldGeneration()!=v.scope().world()||v.nativeStatus()==null||v.nativeStatus().flags()!=0||
            (v.nativeStatus().capabilities()&MOTION_CAPABILITY)==0)return false;
        long now=WorldWriter.clockMillis(),age=System.currentTimeMillis()-v.enemies().receivedMs();
        return age>=0&&age<=200&&v.nativeStatus().atMs()<=now&&now-v.nativeStatus().atMs()<=200;
    }
    private static BlockPos medium(ServerLevel level,AABB box){
        var area=box.deflate(1e-5);
        for(var at:BlockPos.betweenClosed(BlockPos.containing(area.minX,area.minY,area.minZ),BlockPos.containing(area.maxX,area.maxY,area.maxZ))){
            if(!level.hasChunkAt(at)||!SceneFluidGeometry.canOccupy(level,at))continue;var fluid=level.getFluidState(at);
            if((fluid.is(FluidTags.WATER)||fluid.is(FluidTags.LAVA))&&area.intersects(new AABB(at.getX(),at.getY(),at.getZ(),at.getX()+1,at.getY()+fluid.getHeight(level,at),at.getZ()+1)))return at.immutable();
        }
        return null;
    }
    public static void tick(MinecraftServer server){
        var level=server.overworld();var view=WorldMotionWorld.current();
        if(view==null||scope!=null&&!scope.equals(view.scope())){stop();if(view==null)return;}
        scope=view.scope();
        for(var f:new ArrayList<>(flights.values()))advance(f);
        if(!valid(view,level)||!view.shapesCurrent())return;
        long now=WorldWriter.clockMillis();
        for(var e:view.enemies().entries()){
            if(flights.size()>=16||view.requests().pending()>=24)break;
            if(!e.alive()||flights.containsKey(e.key())||now<retry.getOrDefault(e.key(),0L)||e.radius()<10||e.radius()>100||e.halfHeight()>200)continue;
            var proxy=EnemyMirrors.hitAttacker(e.id(),e.spawn());if(proxy==null||proxy.isRemoved())continue;
            var at=medium(level,proxy.getBoundingBox());if(at==null)continue;
            var f=new Flight(view,level,e,proxy,at);flights.put(f.key,f);f.deadline=now+500;f.renewAt=now+200;
            send(view,f,RESERVE,e.location(),SifuFrame.Vec3.ZERO,now);
        }
        retry.keySet().removeIf(k->view.enemies().entries().stream().noneMatch(e->e.key().equals(k)));
    }
    private static Reply result(CompletableFuture<Reply> f){return f==null||!f.isDone()||f.isCompletedExceptionally()||f.isCancelled()?null:f.getNow(null);}
    private static void wake(Flight f){if(!f.queued.compareAndSet(false,true))return;f.level.getServer().execute(()->{f.queued.set(false);if(flights.get(f.key)==f)advance(f);});}
    private static void send(WorldMotionWorld.View v,Flight f,int op,SifuFrame.Vec3 pos,SifuFrame.Vec3 delta,long now){
        f.pendingOp=op;f.pendingDelta=delta;
        if(op==RENEW){f.deadline=now+500;f.renewAt=now+200;}
        f.pending=v.requests().request(f.scope,id->{if(op==RESERVE)f.lease=id;
            return new Command(op,FLUID,id,op==RESERVE?0:f.lease,now,f.source.getMostSignificantBits(),f.source.getLeastSignificantBits(),ACTION,f.level.getGameTime(),
                f.scope.save(),f.scope.map(),v.nativeStatus().shapeRevision(),f.scope.world(),f.key.id(),f.key.spawn(),op==RESERVE||op==RENEW?500:0,pos,delta,
                f.initial.health(),f.initial.radius(),f.initial.halfHeight(),0,f.sourcePos.getX(),f.sourcePos.getY(),f.sourcePos.getZ(),0,0,0,f.stateHash,f.sectionRevision,
                f.binding==null?0:f.binding.actorSerial(),f.binding==null?0:f.binding.componentToken());});
        f.pending.whenComplete((reply,error)->wake(f));
    }
    private static Vec3 rootFeet(WorldMotionWorld.View v,Flight f,Reply reply){
        var p=reply.position();var offset=f.binding.rootOffset();
        return new Vec3(v.placement().mcX(p.x()+offset.x()),v.placement().mcY(p.z()+offset.z()-f.binding.rootHalfHeight()),v.placement().mcZ(p.y()+offset.y()));
    }
    private static void applied(WorldMotionWorld.View v,Flight f,Reply reply){
        poses.put(f.key,new Pose(f.scope,reply,WorldWriter.clockMillis()+600));
        var proxy=EnemyMirrors.hitAttacker(f.key.id(),f.key.spawn());if(proxy==null)return;var p=reply.position();
        proxy.setPos(v.placement().mcX(p.x()),v.placement().mcY(p.z()-f.initial.halfHeight()),v.placement().mcZ(p.y()));
        proxy.updateSignals();
    }
    private static boolean binding(Flight f,Reply r){return r!=null&&r.actorSerial()==f.binding.actorSerial()&&r.componentToken()==f.binding.componentToken()&&r.health()==f.initial.health();}
    private static void retire(Flight f,boolean ok){
        if(!ok&&logs++<80){var r=result(f.pending);LoggerFactory.getLogger("sifucraft").info("[fluid-motion] released target={}/{} phase={} receipt={} native_binding={} stats={}",f.key.id(),f.key.spawn(),f.phase,r==null?-1:r.status(),f.binding!=null,stats());}
        flights.remove(f.key);retry.put(f.key,WorldWriter.clockMillis()+(ok?100:500));if(f.body!=null)f.body.discard();
        if(!ok)failed++;
    }
    private static void cancel(WorldMotionWorld.View v,Flight f,long now){
        if(f.phase==Phase.CANCEL)return;f.phase=Phase.CANCEL;
        if(f.binding!=null&&v!=null&&f.scope.equals(v.requests().scope())&&v.nativeStatus()!=null)send(v,f,CANCEL,f.last.position(),SifuFrame.Vec3.ZERO,now);
        else retire(f,false); // an unobserved reservation remains bounded by its native 500ms deadline
    }
    private static void advance(Flight f){
        var v=WorldMotionWorld.current();long now=WorldWriter.clockMillis();
        if(f.phase==Phase.CANCEL){if(f.pending==null||f.pending.isDone()||now>f.deadline+1000)retire(f,false);return;}
        if(!valid(v,f.level)||!f.scope.equals(v.scope())||now>=f.deadline+(f.phase==Phase.RELEASE?1000:0)){cancel(v,f,now);return;}
        var proxy=EnemyMirrors.hitAttacker(f.key.id(),f.key.spawn());if(proxy==null||proxy.isRemoved()){cancel(v,f,now);return;}
        if(f.pending!=null&&!f.pending.isDone())return;
        // A rejected collision epoch has performed no sweep. Keep the existing
        // short authority while the new geometry ACK arrives, then issue a NEW
        // command against that epoch. Never replay a partial/applied movement.
        var rejection=result(f.pending);
        if(f.binding!=null&&binding(f,rejection)&&(rejection.status()==8||rejection.status()==9)){
            f.retryOp=f.pendingOp;f.pending=null;
        }
        if(f.retryOp!=0){if(!v.shapesCurrent())return;int op=f.retryOp;f.retryOp=0;send(v,f,op,f.last.position(),f.pendingDelta,now);return;}
        if(f.phase==Phase.ACQUIRE){
            var r=result(f.pending);if(r==null||r.status()!=1||r.actorSerial()<=0||r.rootRadius()<10||r.rootHalfHeight()<r.rootRadius()){retire(f,false);return;}
            f.binding=f.last=r;f.pending=null;
            f.body=new VanillaFluidBody(f.level,r.rootRadius(),r.rootHalfHeight(),((long)f.key.id()<<32)^f.key.spawn(),true);
            f.body.setPos(rootFeet(v,f,r));f.body.setDeltaMovement(new Vec3(f.initial.velocity().x()/2000.0,f.initial.velocity().z()/2000.0,f.initial.velocity().y()/2000.0));
            f.phase=Phase.IDLE;acquired++;
            var listener=observer;if(listener!=null)listener.prepared(sample(f));
        }
        if(f.phase==Phase.RENEW){
            var r=result(f.pending);if(!binding(f,r)||r.status()!=2){cancel(v,f,now);return;}f.pending=null;renewed++;
            if(f.resumeMove){f.resumeMove=false;f.phase=Phase.MOVE;sendPart(v,f,now);return;}f.phase=Phase.IDLE;
        }
        if(f.phase==Phase.RELEASE){
            var r=result(f.pending);boolean ok=binding(f,r)&&r.status()==3&&r.restored()==7;
            if(ok){applied(v,f,r);released++;}retire(f,ok);return;
        }
        if(f.phase==Phase.MOVE){
            var r=result(f.pending);if(!binding(f,r)||(r.status()!=2&&r.status()!=4&&r.status()!=5)){cancel(v,f,now);return;}
            f.last=r;applied(v,f,r);f.pending=null;steps++;
            if(r.status()!=2){blocked++;f.part=f.parts;}
            else f.part++;
            if(f.part<f.parts){
                if(now>=f.renewAt){f.resumeMove=true;f.phase=Phase.RENEW;send(v,f,RENEW,f.last.position(),SifuFrame.Vec3.ZERO,now);}else sendPart(v,f,now);
                return;
            }
            var actual=rootFeet(v,f,f.last);var moved=actual.subtract(f.actualStart);var velocity=f.nextVelocity;
            if(Math.abs(moved.x-f.requested.x)>1e-4)velocity=new Vec3(0,velocity.y,velocity.z);
            if(Math.abs(moved.y-f.requested.y)>1e-4){velocity=new Vec3(velocity.x,0,velocity.z);if(f.requested.y<0)f.body.setOnGround(true);}
            if(Math.abs(moved.z-f.requested.z)>1e-4)velocity=new Vec3(velocity.x,velocity.y,0);
            f.body.setPos(actual);f.body.setDeltaMovement(velocity);f.phase=Phase.IDLE;
            var listener=observer;if(listener!=null)listener.completed(sample(f));
        }
        if(f.phase!=Phase.IDLE||!v.shapesCurrent())return;
        if(now>=f.renewAt){f.phase=Phase.RENEW;send(v,f,RENEW,f.last.position(),SifuFrame.Vec3.ZERO,now);return;}
        long tick=f.level.getGameTime();if(tick==f.lastTick)return;f.lastTick=tick;
        if(medium(f.level,f.body.getBoundingBox())==null&&f.body.onGround()){
            f.phase=Phase.RELEASE;send(v,f,RELEASE,f.last.position(),SifuFrame.Vec3.ZERO,now);return;
        }
        f.actualStart=rootFeet(v,f,f.last);f.body.setPos(f.actualStart);
        SifuCollision.withQueryView(v.scene(),()->{f.body.tick();return null;});
        var listener=observer;if(listener!=null)listener.simulated(sample(f));
        f.requested=f.body.position().subtract(f.actualStart);f.nextVelocity=f.body.getDeltaMovement();
        if(!Double.isFinite(f.requested.lengthSqr())||f.requested.length()>3.2){cancel(v,f,now);return;}
        if(f.requested.lengthSqr()<1e-12){f.body.setPos(f.actualStart);if(listener!=null)listener.completed(sample(f));return;}
        f.parts=(int)Math.ceil(f.requested.length()/.1);f.part=0;f.phase=Phase.MOVE;sendPart(v,f,now);
    }
    private static void sendPart(WorldMotionWorld.View v,Flight f,long now){
        var d=f.requested.scale(100.0/f.parts);
        send(v,f,STEP,f.last.position(),new SifuFrame.Vec3((float)d.x,(float)d.z,(float)d.y),now);
    }
    /** A delayed main snapshot must never overwrite a newer physical receipt. */
    public static EnemyFrame.Entry pose(EnemyFrame frame,EnemyFrame.Entry e){
        var p=poses.get(e.key());if(p==null)return e;
        if(p.scope.nativeSession()!=frame.session()||p.scope.world()!=frame.worldGeneration()||WorldWriter.clockMillis()>p.until||frame.frame()>p.reply.nativeFrame()){poses.remove(e.key());return e;}
        return new EnemyFrame.Entry(e.id(),e.spawn(),p.reply.position(),e.yaw(),e.radius(),e.halfHeight(),e.health(),e.maxHealth(),e.flags(),e.faction(),e.velocity());
    }
    public static void stop(){for(var f:flights.values())if(f.body!=null)f.body.discard();flights.clear();retry.clear();poses.clear();scope=null;}
}
