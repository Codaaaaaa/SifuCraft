package dev.sifucraft.blocks;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.bridge.WorldWriter;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.EnemyProxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Server-thread piston transaction: old collision -> actual motion -> vanilla commit -> new collision -> release. */
public final class PistonMachines {
    private PistonMachines(){}
    private enum Phase { PREPARE,MOVE,COMMIT,SHAPES,RELEASE,CANCEL }
    private static final class SourceToken {final UUID id=UUID.randomUUID();long retryAt,lastCommitTick=Long.MIN_VALUE;}
    private static final class Target {
        final EnemyFrame.Key key;final EnemyFrame.Entry entry;final PistonContacts.Source source;
        CompletableFuture<Reply> reserve,cancel,release;Reply binding,last;long lease;
        Target(EnemyFrame.Entry entry,PistonContacts.Source source){key=entry.key();this.entry=entry;this.source=source;}
    }
    private record Work(Target target,PistonContacts.Source source,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,long revision){}
    private static final class Flight {
        final ServerLevel level;final PistonMovingBlockEntity piston;final SourceToken token;final WorldMotionRequests.Scope scope;
        final float progress;final long deadline;final Map<EnemyFrame.Key,Target> targets=new LinkedHashMap<>();
        final List<Work> work=new ArrayList<>();final List<CompletableFuture<Reply>> moves=new ArrayList<>();
        Phase phase=Phase.PREPARE;int dispatched;long commitMotion,commitIndex,lastRevision;
        final AtomicBoolean wakeScheduled=new AtomicBoolean();
        Flight(ServerLevel level,PistonMovingBlockEntity piston,SourceToken token,WorldMotionRequests.Scope scope,long now){
            this.level=level;this.piston=piston;this.token=token;this.scope=scope;progress=piston.getProgress(1);deadline=now+500;
        }
    }
    private record Pose(WorldMotionRequests.Scope scope,SifuFrame.Vec3 position,long frame,long until,boolean released){}
    private static final WeakHashMap<PistonMovingBlockEntity,SourceToken> sources=new WeakHashMap<>();
    private static final IdentityHashMap<PistonMovingBlockEntity,Flight> flights=new IdentityHashMap<>();
    private static final Map<EnemyFrame.Key,PistonMotionLimit.State> budgets=new HashMap<>();
    private static final Map<EnemyFrame.Key,Pose> poses=new HashMap<>();
    private static final ThreadLocal<Set<EnemyFrame.Key>> committing=new ThreadLocal<>();
    private static WorldMotionRequests.Scope scope;
    private static volatile MinecraftServer activeServer;
    private static final AtomicBoolean geometryWake=new AtomicBoolean();
    private static volatile long offeredRevision;
    private static volatile java.util.function.BiConsumer<EnemyFrame.Key,Reply> preparedObserver;
    /** Isolated real-game test observes the physical binding before creating its independent reference. */
    public static void observePrepared(java.util.function.BiConsumer<EnemyFrame.Key,Reply> observer){
        if(observer!=null&&!Boolean.getBoolean("sifucraft.nativePistonSelfTest"))throw new IllegalStateException("isolated fixture only");preparedObserver=observer;
    }
    private static long prepared,applied,committed,cancelled,released,releaseFailed;
    public record Stats(long prepared,long applied,long committed,long cancelled,long released,long releaseFailed,int pending){}
    public static Stats stats(){return new Stats(prepared,applied,committed,cancelled,released,releaseFailed,flights.size());}
    /** Receipt callbacks only queue work. A vanilla ticker is always entered on its owning server thread. */
    private static void wake(Flight f){
        var server=f.level.getServer();if(server.isSameThread()||!f.wakeScheduled.compareAndSet(false,true))return;
        server.execute(()->{f.wakeScheduled.set(false);if(flights.get(f.piston)==f&&f.level.getBlockEntity(f.piston.getBlockPos())==f.piston)
            PistonMovingBlockEntity.tick(f.level,f.piston.getBlockPos(),f.level.getBlockState(f.piston.getBlockPos()),f.piston);});
    }
    private static CompletableFuture<Reply> watch(Flight f,CompletableFuture<Reply> reply){reply.whenComplete((value,error)->wake(f));return reply;}
    /** New applied geometry can unblock release between ordinary 20Hz block-entity ticks. */
    public static void offered(WorldMotionWorld.View view){
        var server=activeServer;if(server==null||view==null||!view.shapesCurrent()||view.geometry().revision()==offeredRevision)return;
        offeredRevision=view.geometry().revision();if(!geometryWake.compareAndSet(false,true))return;
        server.execute(()->{geometryWake.set(false);for(var f:new ArrayList<>(flights.values()))if(f.phase==Phase.SHAPES)wakeFromServer(f);});
    }
    private static void wakeFromServer(Flight f){if(flights.get(f.piston)==f&&f.level.getBlockEntity(f.piston.getBlockPos())==f.piston)
        PistonMovingBlockEntity.tick(f.level,f.piston.getBlockPos(),f.level.getBlockState(f.piston.getBlockPos()),f.piston);}
    public static boolean suppress(Entity entity){var set=committing.get();return set!=null&&entity instanceof EnemyProxy p&&set.contains(p.sifuKey());}
    private static boolean valid(WorldMotionWorld.View view,ServerLevel level){
        return view!=null&&view.scope().equals(view.requests().scope())&&view.scope().save()==PistonMovePlans.saveKey(level)&&
            view.scene()!=null&&view.scene().matchesScope(view.scope().nativeSession(),view.scope().world())&&view.scene().placement().equals(view.placement())&&
            view.enemies()!=null&&view.enemies().session()==view.scope().nativeSession()&&view.enemies().worldGeneration()==view.scope().world();
    }
    private static boolean fresh(WorldMotionWorld.View view){long now=System.currentTimeMillis(),nativeNow=WorldWriter.clockMillis();return view.enemies().receivedMs()<=now&&now-view.enemies().receivedMs()<=200&&
        view.nativeStatus()!=null&&view.nativeStatus().atMs()<=nativeNow&&nativeNow-view.nativeStatus().atMs()<=200&&view.nativeStatus().world()==view.scope().world()&&view.nativeStatus().flags()==0;}
    private static EnemyFrame.Entry entry(WorldMotionWorld.View view,EnemyFrame.Key key){return view.enemies().entries().stream().filter(e->e.key().equals(key)&&e.alive()).findFirst().orElse(null);}
    private static Map<EnemyFrame.Key,PistonPreview.Body> contacts(WorldMotionWorld.View view,PistonPreview.Result preview){
        var touched=new HashSet<EnemyFrame.Key>();for(var m:preview.moves())if(m.enemy()!=null)touched.add(m.enemy());
        var result=new LinkedHashMap<EnemyFrame.Key,PistonPreview.Body>();
        for(var body:preview.bodies())if(body.enemy()!=null&&touched.contains(body.enemy())){
            var proxy=EnemyMirrors.hitAttacker(body.enemy().id(),body.enemy().spawn());var e=entry(view,body.enemy());
            if(proxy!=null&&!proxy.isRemoved()&&proxy.getUUID().equals(body.entity())&&e!=null)result.put(body.enemy(),body);
        }
        return result;
    }
    public static void tick(Level world,BlockPos pos,PistonMovingBlockEntity piston,Runnable original){
        if(!WorldBlocks.ENABLED||!(world instanceof ServerLevel level)||world.dimension()!=Level.OVERWORLD||PistonContacts.observing()||PistonPreview.active()) {original.run();return;}
        var view=WorldMotionWorld.current();
        var flight=flights.get(piston);
        if(flight!=null){advance(view,flight,original);return;}
        if(!valid(view,level)){original.run();return;}
        if(!view.scope().equals(scope)){stop();scope=view.scope();}
        if(piston.getProgress(1)>=1){original.run();return;}
        if(level.getEntities((Entity)null,new AABB(pos).inflate(3),e->e instanceof EnemyProxy p&&EnemyMirrors.hitAttacker(p.sifuKey().id(),p.sifuKey().spawn())==p).isEmpty()){original.run();return;}
        var token=sources.computeIfAbsent(piston,ignored->new SourceToken());long now=WorldWriter.clockMillis();
        if(now<token.retryAt||level.getGameTime()<=token.lastCommitTick)return;
        var preview=PistonPreview.run(level,pos,piston,Math.min(1,piston.getProgress(1)+.5f),view.scene(),budgets,token.id);
        var bodies=contacts(view,preview);
        if(bodies.isEmpty()){original.run();return;}
        if(!fresh(view)||!view.shapesCurrent()||(view.nativeStatus().capabilities()&MOTION_CAPABILITY)==0||flights.size()>=128)return;
        flight=new Flight(level,piston,token,view.scope(),now);flights.put(piston,flight);activeServer=level.getServer();PistonTimeline.track(piston,view.scope());
        for(var key:bodies.keySet()){
            var source=preview.moves().stream().filter(m->key.equals(m.enemy())).findFirst().orElseThrow().source();
            if(source.sectionRevision()==0){cancel(view,flight);return;}
            var target=new Target(entry(view,key),source);flight.targets.put(key,target);var active=flight;
            target.reserve=watch(active,view.requests().request(flight.scope,id->{target.lease=id;return command(active,target,source,RESERVE,id,null,target.entry.location(),SifuFrame.Vec3.ZERO,view.nativeStatus().shapeRevision(),now);}));
        }
    }
    private static void advance(WorldMotionWorld.View view,Flight f,Runnable original){
        long now=WorldWriter.clockMillis();
        if(!valid(view,f.level)||!f.scope.equals(view.scope())||f.piston.isRemoved()||f.level.getBlockEntity(f.piston.getBlockPos())!=f.piston)cancel(view,f);
        if(f.phase!=Phase.SHAPES&&f.phase!=Phase.RELEASE&&f.piston.getProgress(1)!=f.progress)cancel(view,f);
        // A sent release has already handed back authority natively. A delayed
        // receipt may be awaited without extending the native 500ms hard limit.
        if(now>=f.deadline&&f.phase!=Phase.CANCEL&&f.phase!=Phase.RELEASE)cancel(view,f);
        if(f.phase==Phase.CANCEL){
            for(var t:f.targets.values()){
                bind(t);if(t.binding!=null&&t.cancel==null&&view!=null&&f.scope.equals(view.requests().scope()))
                    t.cancel=watch(f,view.requests().request(f.scope,id->command(f,t,t.source,CANCEL,id,t.binding,t.binding.position(),SifuFrame.Vec3.ZERO,view.nativeStatus()==null?0:view.nativeStatus().shapeRevision(),now)));
            }
            if(now>=f.deadline+1500||f.targets.values().stream().allMatch(t->done(t.reserve)&&(t.binding==null||done(t.cancel))))retire(f,now);
            return;
        }
        if(!fresh(view))return;
        if(f.phase==Phase.PREPARE){
            boolean waiting=false;
            for(var t:f.targets.values()){
                if(!done(t.reserve)){waiting=true;continue;}
                if(!bind(t)){cancel(view,f);return;}
            }
            if(waiting||!view.shapesCurrent())return;
            var bindings=new HashMap<EnemyFrame.Key,Reply>();for(var t:f.targets.values()){bindings.put(t.key,t.binding);var observer=preparedObserver;if(observer!=null)observer.accept(t.key,t.binding);}
            var preview=PistonPreview.run(f.level,f.piston.getBlockPos(),f.piston,Math.min(1,f.progress+.5f),view.scene(),budgets,f.token.id,bindings);
            var bodies=contacts(view,preview);
            if(!bodies.keySet().equals(f.targets.keySet())){cancel(view,f);return;}
            for(var t:f.targets.values()){
                var body=bodies.get(t.key);var current=entry(view,t.key);
                if(current==null||!samePosition(current.location(),t.binding.position(),1)){cancel(view,f);return;}
                var predicted=t.binding.position();
                for(var move:preview.moves())if(t.key.equals(move.enemy())){
                    // A clipped/no-budget proposal jams this step. It must not install
                    // a new pusher through an unmoved physical body.
                    if(move.limited().distanceToSqr(move.collided())>1e-10||move.collided().lengthSqr()<1e-12){cancel(view,f);return;}
                    var delta=new SifuFrame.Vec3((float)(move.collided().x*100),(float)(move.collided().z*100),(float)(move.collided().y*100));
                    int steps=(int)Math.ceil(Math.sqrt((double)delta.x()*delta.x()+(double)delta.y()*delta.y()+(double)delta.z()*delta.z())/10);
                    for(int i=0;i<steps;++i){
                        var part=new SifuFrame.Vec3(delta.x()/steps,delta.y()/steps,delta.z()/steps);
                        f.work.add(new Work(t,move.source(),predicted,part,view.nativeStatus().shapeRevision()));predicted=plus(predicted,part);
                    }
                }
                budgets.put(t.key,body.budget());
            }
            f.phase=Phase.MOVE;prepared+=f.targets.size();
        }
        if(f.phase==Phase.MOVE){
            while(f.dispatched<f.work.size()&&view.requests().pending()<32){
                var work=f.work.get(f.dispatched);
                var section=WorldBlocks.index().snapshot(SectionPos.of(work.source.pos()).asLong());
                if(section==null||section.revision()!=work.source.sectionRevision()){cancel(view,f);return;}
                f.moves.add(watch(f,view.requests().request(f.scope,id->command(f,work.target,work.source,STEP,id,work.target.binding,work.position,work.delta,work.revision,now))));f.dispatched++;
            }
            for(var future:f.moves)if(done(future)){var reply=result(future);if(reply==null||reply.status()!=2){cancel(view,f);return;}}
            if(f.dispatched<f.work.size()||f.moves.stream().anyMatch(p->!done(p)))return;
            for(int i=0;i<f.moves.size();++i){var reply=result(f.moves.get(i));var target=f.work.get(i).target;target.last=reply;
                poses.put(target.key,new Pose(f.scope,reply.position(),reply.nativeFrame(),now+1500,false));applied++;f.lastRevision=Math.max(f.lastRevision,reply.shapeRevision());}
            f.phase=Phase.COMMIT;
        }
        if(f.phase==Phase.COMMIT){
            // Every in-flight move using the old collision must finish before
            // any lane publishes the next collision version.
            if(flights.values().stream().anyMatch(other->other!=f&&(other.phase==Phase.PREPARE||other.phase==Phase.MOVE)))return;
            // The ordinary commit may move MC bodies. Native proxies have already
            // moved physically and must never receive vanilla's noPhysics move again.
            var previous=committing.get();committing.set(Set.copyOf(f.targets.keySet()));
            try{original.run();}finally{if(previous==null)committing.remove();else committing.set(previous);}
            if(f.piston.getProgress(1)!=Math.min(1,f.progress+.5f)){cancel(view,f);return;}
            f.token.lastCommitTick=f.level.getGameTime();
            f.commitMotion=ShapeChanges.revision()+1;f.commitIndex=WorldBlocks.index().revision();f.phase=Phase.SHAPES;committed++;
        }
        if(f.phase==Phase.SHAPES){
            var geometry=view.geometry();
            if(!geometry.current()||geometry.motion()<f.commitMotion||geometry.index()<f.commitIndex||geometry.revision()<=f.lastRevision||view.nativeStatus().shapeRevision()<geometry.revision())return;
            for(var t:f.targets.values())t.release=watch(f,view.requests().request(f.scope,id->command(f,t,t.source,RELEASE,id,t.binding,t.last.position(),SifuFrame.Vec3.ZERO,view.nativeStatus().shapeRevision(),now)));
            f.phase=Phase.RELEASE;
        }
        if(f.phase==Phase.RELEASE){
            if(f.targets.values().stream().anyMatch(t->!done(t.release)))return;
            for(var t:f.targets.values()){
                var receipt=result(t.release);
                if(receipt!=null&&receipt.status()==3&&receipt.restored()==7&&receipt.actorSerial()==t.binding.actorSerial()&&receipt.componentToken()==t.binding.componentToken()){
                    poses.put(t.key,new Pose(f.scope,receipt.position(),receipt.nativeFrame(),now+500,true));
                    released++;
                }else releaseFailed++;
            }
            retire(f,now);
        }
    }
    private static void cancel(WorldMotionWorld.View view,Flight f){if(f.phase!=Phase.CANCEL){f.phase=Phase.CANCEL;cancelled++;}if(view==null)f.token.retryAt=Long.MAX_VALUE;}
    private static void retire(Flight f,long now){flights.remove(f.piston);f.token.retryAt=now+50;}
    private static boolean done(CompletableFuture<?> future){return future!=null&&future.isDone();}
    private static Reply result(CompletableFuture<Reply> future){if(!done(future)||future.isCompletedExceptionally()||future.isCancelled())return null;return future.getNow(null);}
    private static boolean bind(Target target){var r=result(target.reserve);if(r==null||r.status()!=1||r.actorSerial()<=0||r.componentToken()==0||r.rootRadius()<10||r.rootHalfHeight()<r.rootRadius())return false;target.binding=r;return true;}
    private static SifuFrame.Vec3 plus(SifuFrame.Vec3 a,SifuFrame.Vec3 b){return new SifuFrame.Vec3(a.x()+b.x(),a.y()+b.y(),a.z()+b.z());}
    private static boolean samePosition(SifuFrame.Vec3 a,SifuFrame.Vec3 b,float cm){double x=a.x()-b.x(),y=a.y()-b.y(),z=a.z()-b.z();return x*x+y*y+z*z<=cm*cm;}
    private static Command command(Flight f,Target t,PistonContacts.Source source,int op,long id,Reply binding,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,long revision,long now){
        long state=ShapeProtocol.mapHash(source.movedState());if(state==0)state=1;var pos=source.pos();
        return new Command(op,MACHINE,id,op==RESERVE?0:t.lease,now,source.capture().getMostSignificantBits(),source.capture().getLeastSignificantBits(),source.action(),source.tick(),
            f.scope.save(),f.scope.map(),revision,f.scope.world(),t.key.id(),t.key.spawn(),op==RESERVE?500:0,position,delta,t.entry.health(),t.entry.radius(),t.entry.halfHeight(),0,
            pos.getX(),pos.getY(),pos.getZ(),source.progress(),source.nextProgress(),0,state,source.sectionRevision(),binding==null?0:binding.actorSerial(),binding==null?0:binding.componentToken());
    }
    /** Server tick cleanup also covers a removed/unloaded BE which will no longer call its own ticker. */
    public static void update(MinecraftServer server){
        PistonTimeline.prune(server);
        var view=WorldMotionWorld.current();if(view==null||scope==null||!scope.equals(view.scope())){stop();return;}
        for(var f:new ArrayList<>(flights.values()))if(f.phase==Phase.CANCEL||f.piston.isRemoved()||f.level.getBlockEntity(f.piston.getBlockPos())!=f.piston)advance(view,f,()->{});
        var alive=new HashSet<EnemyFrame.Key>();if(view.enemies()!=null)for(var e:view.enemies().entries())if(e.alive())alive.add(e.key());
        budgets.keySet().retainAll(alive);poses.keySet().retainAll(alive);
    }
    /** Avoid applying an older enemy table over a newer actual-motion receipt. */
    public static EnemyFrame.Entry pose(EnemyFrame frame,EnemyFrame.Entry entry){
        var p=poses.get(entry.key());if(p==null)return entry;long now=WorldWriter.clockMillis();
        if(p.scope.nativeSession()!=frame.session()||p.scope.world()!=frame.worldGeneration()||now>=p.until||
           frame.frame()>p.frame&&(p.released||samePosition(entry.location(),p.position,1))){poses.remove(entry.key());return entry;}
        return new EnemyFrame.Entry(entry.id(),entry.spawn(),p.position,entry.yaw(),entry.radius(),entry.halfHeight(),entry.health(),entry.maxHealth(),entry.flags(),entry.faction(),entry.velocity());
    }
    public static void stop(){flights.clear();sources.clear();budgets.clear();poses.clear();scope=null;activeServer=null;offeredRevision=0;geometryWake.set(false);PistonTimeline.stop();}
}
