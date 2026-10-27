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
    private static final class SourceToken {final UUID id;final Map<EnemyFrame.Key,Double> spent=new HashMap<>();long retryAt,lastCommitTick=Long.MIN_VALUE;SourceToken(){this(UUID.randomUUID());}SourceToken(UUID id){this.id=id;}}
    private static final class Target {
        final EnemyFrame.Key key;final EnemyFrame.Entry entry;final PistonContacts.Source source;
        CompletableFuture<Reply> reserve,cancel,release;Reply binding,last;long lease;
        Vec3 velocity=Vec3.ZERO;
        Target(EnemyFrame.Entry entry,PistonContacts.Source source){key=entry.key();this.entry=entry;this.source=source;}
    }
    private record Work(Target target,PistonContacts.Source source,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,long revision){}
    private static final class Flight {
        final ServerLevel level;final PistonMovingBlockEntity piston;final SourceToken token;final WorldMotionRequests.Scope scope;
        final List<PistonMovingBlockEntity> members;final Map<PistonMovingBlockEntity,Float> progressByMember=new IdentityHashMap<>();
        final float progress;final long deadline;final Map<EnemyFrame.Key,Target> targets=new LinkedHashMap<>();
        final List<Work> work=new ArrayList<>();final List<CompletableFuture<Reply>> moves=new ArrayList<>();
        final Set<Long> reported=new HashSet<>();
        Phase phase=Phase.PREPARE;int dispatched;long commitMotion,commitIndex,lastRevision;
        final AtomicBoolean wakeScheduled=new AtomicBoolean();
        Flight(ServerLevel level,PistonMovingBlockEntity piston,List<PistonMovingBlockEntity> members,SourceToken token,WorldMotionRequests.Scope scope,long now){
            this.level=level;this.piston=piston;this.token=token;this.scope=scope;progress=piston.getProgress(1);deadline=now+500;
            this.members=List.copyOf(members);for(var member:members)progressByMember.put(member,member.getProgress(1));
        }
    }
    private record Pose(WorldMotionRequests.Scope scope,SifuFrame.Vec3 position,long frame,long until,boolean released){}
    private static final WeakHashMap<PistonMovingBlockEntity,SourceToken> sources=new WeakHashMap<>();
    private static final Map<UUID,SourceToken> groupTokens=new HashMap<>();
    private static final IdentityHashMap<PistonMovingBlockEntity,Flight> flights=new IdentityHashMap<>();
    private static final Map<EnemyFrame.Key,PistonMotionLimit.State> budgets=new HashMap<>();
    private static final Map<EnemyFrame.Key,Pose> poses=new HashMap<>();
    private static final ThreadLocal<Set<EnemyFrame.Key>> committing=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> groupCommit=ThreadLocal.withInitial(()->false);
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
    public static Stats stats(){return new Stats(prepared,applied,committed,cancelled,released,releaseFailed,(int)flights.values().stream().distinct().count());}
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
        server.execute(()->{geometryWake.set(false);for(var f:new HashSet<>(flights.values()))if(f.phase==Phase.SHAPES)wakeFromServer(f);});
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
        if(groupCommit.get()||!WorldBlocks.ENABLED||!(world instanceof ServerLevel level)||world.dimension()!=Level.OVERWORLD||PistonContacts.observing()||PistonPreview.active()) {original.run();return;}
        var view=WorldMotionWorld.current();
        var flight=flights.get(piston);
        if(flight!=null){var active=flight;advance(view,active,()->originals(active.level,active.members));return;}
        if(!valid(view,level)){original.run();return;}
        if(!view.scope().equals(scope)){stop();scope=view.scope();}
        var membership=PistonActions.member(piston);var members=membership==null?List.of(piston):PistonActions.members(level,membership);
        if(members.isEmpty())return;
        var token=membership==null?sources.computeIfAbsent(piston,ignored->new SourceToken()):groupTokens.computeIfAbsent(membership.token().id(),SourceToken::new);long now=WorldWriter.clockMillis();
        if(now<token.retryAt||level.getGameTime()<=token.lastCommitTick)return;
        if(members.stream().allMatch(p->p.getProgress(1)>=1)){originals(level,members);token.lastCommitTick=level.getGameTime();return;}
        if(members.stream().allMatch(p->level.getEntities((Entity)null,new AABB(p.getBlockPos()).inflate(3),e->e instanceof EnemyProxy enemy&&EnemyMirrors.hitAttacker(enemy.sifuKey().id(),enemy.sifuKey().spawn())==enemy).isEmpty())){
            originals(level,members);token.lastCommitTick=level.getGameTime();return;
        }
        var preview=PistonPreview.group(level,members,view.scene(),budgets,token.id,Map.of());
        var bodies=contacts(view,preview);
        if(bodies.isEmpty()){originals(level,members);token.lastCommitTick=level.getGameTime();return;}
        if(!fresh(view)||!view.shapesCurrent()||(view.nativeStatus().capabilities()&MOTION_CAPABILITY)==0||flights.size()>=128)return;
        boolean yielding=false;for(var key:bodies.keySet())yielding|=dev.sifucraft.combat.FluidMotion.yieldToMachine(key);
        if(yielding){for(var member:members)PistonTimeline.track(member,view.scope());return;}
        flight=new Flight(level,piston,members,token,view.scope(),now);for(var member:members){flights.put(member,flight);PistonTimeline.track(member,view.scope());}activeServer=level.getServer();
        for(var key:bodies.keySet()){
            var source=preview.moves().stream().filter(m->key.equals(m.enemy())).findFirst().orElseThrow().source();
            if(source.sectionRevision()==0){cancel(view,flight);return;}
            var target=new Target(entry(view,key),source);flight.targets.put(key,target);var active=flight;
            target.reserve=watch(active,view.requests().request(flight.scope,id->{target.lease=id;return command(active,target,source,RESERVE,id,null,target.entry.location(),SifuFrame.Vec3.ZERO,view.nativeStatus().shapeRevision(),now);}));
        }
    }
    private static void advance(WorldMotionWorld.View view,Flight f,Runnable original){
        long now=WorldWriter.clockMillis();
        if(!valid(view,f.level)||!f.scope.equals(view.scope())||f.members.stream().anyMatch(p->p.isRemoved()||f.level.getBlockEntity(p.getBlockPos())!=p))cancel(view,f);
        if(f.phase!=Phase.SHAPES&&f.phase!=Phase.RELEASE&&f.members.stream().anyMatch(p->p.getProgress(1)!=f.progressByMember.get(p)))cancel(view,f);
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
            var preview=PistonPreview.group(f.level,f.members,view.scene(),budgets,f.token.id,bindings);
            var bodies=contacts(view,preview);
            if(!bodies.keySet().equals(f.targets.keySet())){cancel(view,f);return;}
            for(var t:f.targets.values()){
                var body=bodies.get(t.key);var current=entry(view,t.key);
                t.velocity=body.velocity();
                if(current==null||!samePosition(current.location(),t.binding.position(),1)){cancel(view,f);return;}
                double requested=preview.moves().stream().filter(m->t.key.equals(m.enemy())).mapToDouble(m->m.collided().length()).sum();
                if(f.token.spent.getOrDefault(t.key,0.0)+requested>1.010001){cancel(view,f);return;}
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
                // Keep the full action's bound across half-step leases and failed
                // receipts. A partial/unknown sweep never refunds its request.
                double distance=Math.sqrt((double)work.delta.x()*work.delta.x()+(double)work.delta.y()*work.delta.y()+(double)work.delta.z()*work.delta.z())/100;
                f.token.spent.merge(work.target.key,distance,Double::sum);
                f.moves.add(watch(f,view.requests().request(f.scope,id->command(f,work.target,work.source,STEP,id,work.target.binding,work.position,work.delta,work.revision,now))));f.dispatched++;
            }
            for(int i=0;i<f.moves.size();i++)if(done(f.moves.get(i))){
                var reply=result(f.moves.get(i));if(reply==null||reply.status()!=2){cancel(view,f);return;}
                if(!f.reported.add(reply.id()))continue;var target=f.work.get(i).target;
                if(target.last==null||reply.id()>target.last.id()){
                    target.last=reply;poses.put(target.key,new Pose(f.scope,reply.position(),reply.nativeFrame(),now+1500,false));updateProxy(view,target,reply);
                }
                applied++;f.lastRevision=Math.max(f.lastRevision,reply.shapeRevision());
            }
            if(f.dispatched<f.work.size()||f.moves.stream().anyMatch(p->!done(p)))return;
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
            if(f.members.stream().anyMatch(p->p.getProgress(1)!=Math.min(1,f.progressByMember.get(p)+.5f))){cancel(view,f);return;}
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
                    updateProxy(view,t,receipt);
                    dev.sifucraft.combat.FluidMotion.machineReleased(f.level,f.scope,t.key,t.binding,receipt,t.velocity,t.source);
                    released++;
                }else releaseFailed++;
            }
            retire(f,now);
        }
    }
    private static void cancel(WorldMotionWorld.View view,Flight f){if(f.phase!=Phase.CANCEL){f.phase=Phase.CANCEL;cancelled++;}if(view==null)f.token.retryAt=Long.MAX_VALUE;}
    private static void retire(Flight f,long now){for(var member:f.members)flights.remove(member,f);f.token.retryAt=now+50;}
    private static void originals(ServerLevel level,List<PistonMovingBlockEntity> members){
        boolean previous=groupCommit.get();groupCommit.set(true);
        try{for(var member:members)if(!member.isRemoved()&&level.getBlockEntity(member.getBlockPos())==member)PistonMovingBlockEntity.tick(level,member.getBlockPos(),level.getBlockState(member.getBlockPos()),member);}
        finally{groupCommit.set(previous);}
    }
    private static void updateProxy(WorldMotionWorld.View view,Target target,Reply reply){
        var proxy=EnemyMirrors.hitAttacker(target.key.id(),target.key.spawn());if(proxy==null)return;var p=reply.position();
        proxy.setPos(view.placement().mcX(p.x()),view.placement().mcY(p.z()-target.entry.halfHeight()),view.placement().mcZ(p.y()));proxy.updateSignals();
    }
    private static boolean done(CompletableFuture<?> future){return future!=null&&future.isDone();}
    private static Reply result(CompletableFuture<Reply> future){if(!done(future)||future.isCompletedExceptionally()||future.isCancelled())return null;return future.getNow(null);}
    private static boolean bind(Target target){var r=result(target.reserve);if(r==null||r.status()!=1||r.actorSerial()<=0||r.componentToken()==0||r.rootRadius()<10||r.rootHalfHeight()<r.rootRadius())return false;target.binding=r;return true;}
    private static SifuFrame.Vec3 plus(SifuFrame.Vec3 a,SifuFrame.Vec3 b){return new SifuFrame.Vec3(a.x()+b.x(),a.y()+b.y(),a.z()+b.z());}
    private static boolean samePosition(SifuFrame.Vec3 a,SifuFrame.Vec3 b,float cm){double x=a.x()-b.x(),y=a.y()-b.y(),z=a.z()-b.z();return x*x+y*y+z*z<=cm*cm;}
    private static Command command(Flight f,Target t,PistonContacts.Source source,int op,long id,Reply binding,SifuFrame.Vec3 position,SifuFrame.Vec3 delta,long revision,long now){
        var owner=t.source;long state=ShapeProtocol.mapHash(owner.movedState());if(state==0)state=1;var pos=owner.pos();
        return new Command(op,MACHINE,id,op==RESERVE?0:t.lease,now,owner.capture().getMostSignificantBits(),owner.capture().getLeastSignificantBits(),owner.action(),source.tick(),
            f.scope.save(),f.scope.map(),revision,f.scope.world(),t.key.id(),t.key.spawn(),op==RESERVE?500:0,position,delta,t.entry.health(),t.entry.radius(),t.entry.halfHeight(),0,
            pos.getX(),pos.getY(),pos.getZ(),source.progress(),source.nextProgress(),0,state,source.sectionRevision(),binding==null?0:binding.actorSerial(),binding==null?0:binding.componentToken());
    }
    /** Server tick cleanup also covers a removed/unloaded BE which will no longer call its own ticker. */
    public static void update(MinecraftServer server){
        PistonTimeline.prune(server);
        var view=WorldMotionWorld.current();if(view==null||scope==null||!scope.equals(view.scope())){stop();return;}
        for(var f:flights.values().stream().distinct().toList())if(f.phase==Phase.CANCEL||f.members.stream().anyMatch(p->p.isRemoved()||f.level.getBlockEntity(p.getBlockPos())!=p))advance(view,f,()->{});
        var alive=new HashSet<EnemyFrame.Key>();if(view.enemies()!=null)for(var e:view.enemies().entries())if(e.alive())alive.add(e.key());
        budgets.keySet().retainAll(alive);poses.keySet().retainAll(alive);
        var tokens=PistonActions.liveTokens();for(var flight:flights.values())tokens.add(flight.token.id);groupTokens.keySet().retainAll(tokens);
    }
    /** Avoid applying an older enemy table over a newer actual-motion receipt. */
    public static EnemyFrame.Entry pose(EnemyFrame frame,EnemyFrame.Entry entry){
        var p=poses.get(entry.key());if(p==null)return entry;long now=WorldWriter.clockMillis();
        if(p.scope.nativeSession()!=frame.session()||p.scope.world()!=frame.worldGeneration()||now>=p.until||
           frame.frame()>p.frame&&(p.released||samePosition(entry.location(),p.position,1))){poses.remove(entry.key());return entry;}
        return new EnemyFrame.Entry(entry.id(),entry.spawn(),p.position,entry.yaw(),entry.radius(),entry.halfHeight(),entry.health(),entry.maxHealth(),entry.flags(),entry.faction(),entry.velocity());
    }
    public static void stop(){flights.clear();sources.clear();groupTokens.clear();budgets.clear();poses.clear();scope=null;activeServer=null;offeredRevision=0;geometryWake.set(false);PistonTimeline.stop();}
}
