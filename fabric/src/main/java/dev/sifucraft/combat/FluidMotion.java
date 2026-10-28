package dev.sifucraft.combat;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.PistonMovePlans;
import dev.sifucraft.blocks.PistonContacts;
import dev.sifucraft.blocks.PistonMachines;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.blocks.NativeMotionSubjects;
import dev.sifucraft.blocks.PlayerSignalBody;
import dev.sifucraft.blocks.PlayerSignalContacts;
import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.bridge.WorldWriter;
import dev.sifucraft.collision.SifuCollision;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Original 20Hz fluid integration, actual native capsule sweeps, short renewed world leases. */
public final class FluidMotion {
    private FluidMotion(){}
    private static final long ACTION=ShapeProtocol.mapHash("sifucraft:fluid");
    private enum Phase { ACQUIRE,IDLE,MOVE,RENEW,RELEASE,CANCEL }
    private record Handoff(WorldMotionRequests.Scope scope,Reply binding,Reply release,Vec3 velocity,PistonContacts.Source source,boolean supported,boolean settling,long at,long playerStamp){}
    private record Impulse(WorldMotionRequests.Scope scope,UUID source,Vec3 velocity,float health,long actorStamp,long at,int authority){}
    private static final class Flight {
        final EnemyFrame.Key key;final EnemyFrame.Entry initial;final WorldMotionRequests.Scope scope;final ServerLevel level;final UUID source;
        final AtomicBoolean queued=new AtomicBoolean();
        final BlockPos sourcePos;final long stateHash,sectionRevision;
        final long action;final Vec3 initialVelocity;
        final boolean machine;final long playerStamp;
        final int authoritySource;final long actorStamp;
        final PistonContacts.Source machineSource;
        Phase phase=Phase.ACQUIRE;CompletableFuture<Reply> pending;Reply binding,last;
        List<CompletableFuture<Reply>> burst=List.of();
        LivingEntity body;MotionPrediction prediction;long lease,deadline,renewAt,lastTick=Long.MIN_VALUE;Vec3 requested,actualStart,nextVelocity;int parts,part;
        int pendingOp,retryOp;SifuFrame.Vec3 pendingDelta=SifuFrame.Vec3.ZERO;
        boolean resumeMove;
        boolean damageYield;
        boolean playerYield;
        boolean machinePending,machineYield;long yieldUntilTick=Long.MIN_VALUE;
        boolean partial,nativeGround;Vec3 contactNormal=Vec3.ZERO;SifuCollision.Active groundScene;long groundRevision;
        Flight(WorldMotionWorld.View view,ServerLevel level,EnemyFrame.Entry e,Entity proxy,BlockPos at,Handoff handoff,Impulse impulse){
            key=e.key();initial=e;scope=view.scope();this.level=level;source=impulse!=null?impulse.source:handoff==null?proxy.getUUID():handoff.source.capture();sourcePos=handoff==null?at:handoff.source.pos();action=impulse!=null?ShapeProtocol.mapHash(impulse.authority==EXPLOSION?"sifucraft:explosion_motion":"sifucraft:passenger_fall"):handoff==null?ACTION:handoff.source.action();
            machine=handoff!=null;
            authoritySource=impulse==null?FLUID:impulse.authority;actorStamp=impulse==null?0:impulse.actorStamp;
            playerStamp=NativeMotionSubjects.player(key)?view.nativeStatus().playerStamp():0;
            machineSource=handoff==null?null:handoff.source;
            initialVelocity=impulse!=null?impulse.velocity:handoff==null?new Vec3(e.velocity().x()/2000.0,e.velocity().z()/2000.0,e.velocity().y()/2000.0):handoff.velocity;
            long hash=ShapeProtocol.mapHash(handoff==null?BlockStateKey.encode(level.getBlockState(at)):handoff.source.movedState());stateHash=hash==0?1:hash;
            var section=WorldBlocks.index().snapshot(SectionPos.of(at).asLong());sectionRevision=handoff==null?(section==null?0:section.revision()):handoff.source.sectionRevision();
        }
    }
    private record Pose(WorldMotionRequests.Scope scope,Reply reply,long until){}
    private static final Map<EnemyFrame.Key,Flight> flights=new HashMap<>();
    private static final Map<EnemyFrame.Key,Long> retry=new HashMap<>();
    private static final Map<EnemyFrame.Key,Pose> poses=new HashMap<>();
    private static final Map<EnemyFrame.Key,Handoff> handoffs=new HashMap<>();
    private static final Map<EnemyFrame.Key,Impulse> impulses=new HashMap<>();
    /** The final vanilla explosion vector, after native HP acknowledgement; it is not a light-hit impulse. */
    static void explosionImpulse(EnemyProxy proxy,WorldMotionRequests.Scope expected,UUID source,Vec3 velocity,Reply receipt){
        var v=WorldMotionWorld.current();
        if(receipt==null||(receipt.status()!=2&&receipt.status()!=4)||receipt.health()<=0||receipt.actorSerial()<=0||
            v==null||!expected.equals(v.scope())||!sameWorld(v,(ServerLevel)proxy.level())||v.nativeStatus()==null||
            (v.nativeStatus().capabilities()&EXPLOSION_CAPABILITY)==0||!Double.isFinite(velocity.lengthSqr()))return;
        var old=impulses.get(proxy.sifuKey());
        if(old!=null&&old.scope.equals(expected)&&old.source.equals(source))return;
        if(old!=null&&old.scope.equals(expected))velocity=velocity.add(old.velocity);
        impulses.put(proxy.sifuKey(),new Impulse(expected,source,velocity,receipt.health(),receipt.actorSerial(),WorldWriter.clockMillis(),EXPLOSION));
        retry.remove(proxy.sifuKey());
    }
    /** A genuine passenger release continues gravity/water once, without inventing a piston source. */
    static void passengerReleased(EnemyProxy proxy,WorldMotionRequests.Scope expected,UUID boat,Reply binding,Reply release,Vec3 velocity){
        var v=WorldMotionWorld.current();
        if(v==null||!expected.equals(v.scope())||binding==null||release==null||release.restored()!=7||
            release.actorSerial()!=binding.actorSerial()||release.componentToken()!=binding.componentToken()||release.health()<=0||
            !Double.isFinite(velocity.lengthSqr())||(release.status()!=3&&release.status()!=6))return;
        var pending=impulses.get(proxy.sifuKey());
        if(pending!=null&&pending.scope.equals(expected)&&pending.authority==EXPLOSION)return;
        impulses.put(proxy.sifuKey(),new Impulse(expected,boat,velocity,release.health(),release.actorSerial(),WorldWriter.clockMillis(),FLUID));
        retry.remove(proxy.sifuKey());
    }
    private record Momentum(WorldMotionRequests.Scope scope,Vec3 velocity,long until){}
    private static final Map<EnemyFrame.Key,Momentum> momentum=new HashMap<>();
    private static WorldMotionRequests.Scope scope;
    private static long acquired,steps,blocked,renewed,released,failed;private static int logs;
    public record Stats(long acquired,long steps,long blocked,long renewed,long released,long failed,int active){}
    public static Stats stats(){return new Stats(acquired,steps,blocked,renewed,released,failed,flights.size());}
    /** A piston preview must see the velocity of the single physical authority,
     * rather than the hit mirror's deliberately zeroed passive velocity. */
    public static Vec3 pistonVelocity(EnemyFrame.Key key,Vec3 fallback){
        var flight=flights.get(key);if(flight!=null&&flight.body!=null)return flight.body.getDeltaMovement();
        var old=momentum.get(key);return old!=null&&old.scope.equals(scope)&&WorldWriter.clockMillis()<old.until?old.velocity:fallback;
    }
    /** Finish an already-issued original physics tick before re-evaluating the
     * contact. This prevents a piston from capturing an intermediate 10cm pose. */
    public static boolean yieldToMachine(EnemyFrame.Key key,WorldMotionWorld.View view){
        long now=WorldWriter.clockMillis();var f=flights.get(key);
        if(f==null){
            var incoming=handoffs.get(key);
            if(incoming!=null&&incoming.velocity.lengthSqr()>1e-10&&now-incoming.at<=500){retry.remove(key);return true;}
            retry.put(key,now+500);return false;
        }
        if(f.phase==Phase.CANCEL||f.phase==Phase.RELEASE)return true;
        if(f.binding==null||f.lastTick==Long.MIN_VALUE||f.phase==Phase.MOVE||f.phase==Phase.RENEW){f.machinePending=true;return true;}
        if(view==null||view.nativeStatus()==null||!f.scope.equals(view.scope()))return true;
        retry.put(key,now+500);momentum.put(key,new Momentum(f.scope,f.body.getDeltaMovement(),now+500));f.machineYield=true;f.phase=Phase.RELEASE;
        send(view,f,RELEASE,f.last.position(),SifuFrame.Vec3.ZERO,now);return true;
    }
    public record Sample(EnemyFrame.Key key,Vec3 position,Vec3 velocity,float width,float height,long seed,int entityId,long tick,boolean grounded,int entityTick){}
    public interface Observer {void prepared(Sample initial);default void beforeSimulation(Sample initial,VanillaFluidBody body){}void simulated(Sample planned);void completed(Sample actual);}
    private static volatile Observer observer;
    public static void observe(Observer value){if(value!=null&&!Boolean.getBoolean("sifucraft.fluidMotionSelfTest")&&!Boolean.getBoolean("sifucraft.bubbleMatrixSelfTest")&&!Boolean.getBoolean("sifucraft.nativePlayerPistonSelfTest")&&!Boolean.getBoolean("sifucraft.nativeLiftSelfTest")&&!Boolean.getBoolean("sifucraft.pistonAftermathSelfTest")&&!Boolean.getBoolean("sifucraft.fluidPartialSelfTest"))throw new IllegalStateException("isolated fixture only");observer=value;}
    private static Sample sample(Flight f){return new Sample(f.key,f.body.position(),f.body.getDeltaMovement(),f.body.getBbWidth(),f.body.getBbHeight(),((long)f.key.id()<<32)^f.key.spawn(),f.body.getId(),f.lastTick,f.body.onGround(),f.body.tickCount);}
    private static boolean sameWorld(WorldMotionWorld.View v,ServerLevel level){
        if(v==null||!WorldBlocks.ENABLED||!v.scope().equals(v.requests().scope())||v.scope().save()!=PistonMovePlans.saveKey(level)||v.scene()==null||
            !v.scene().matchesScope(v.scope().nativeSession(),v.scope().world())||!v.placement().equals(v.scene().placement())||v.enemies()==null||
            v.enemies().session()!=v.scope().nativeSession()||v.enemies().worldGeneration()!=v.scope().world())return false;
        return true;
    }
    private static boolean available(WorldMotionWorld.View v,ServerLevel level){
        return sameWorld(v,level)&&v.nativeStatus()!=null&&v.nativeStatus().flags()==0;
    }
    private static boolean valid(WorldMotionWorld.View v,ServerLevel level){
        if(!available(v,level)||(v.nativeStatus().capabilities()&MOTION_CAPABILITY)==0)return false;
        return fresh(v);
    }
    private static boolean fresh(WorldMotionWorld.View v){
        long now=WorldWriter.clockMillis(),age=System.currentTimeMillis()-v.enemies().receivedMs();
        return age>=0&&age<=200&&v.nativeStatus().atMs()<=now&&now-v.nativeStatus().atMs()<=200;
    }
    static boolean currentGeometry(WorldMotionWorld.View v){
        var index=WorldBlocks.index();
        return v.shapesCurrent()&&index!=null&&v.geometry().index()==index.revision()&&
            v.geometry().motion()==dev.sifucraft.blocks.ShapeChanges.revision()&&v.nativeStatus().shapeRevision()==v.geometry().revision();
    }
    private static BlockPos medium(ServerLevel level,AABB box){
        var area=box.deflate(1e-5);
        for(var at:BlockPos.betweenClosed(BlockPos.containing(area.minX,area.minY,area.minZ),BlockPos.containing(area.maxX,area.maxY,area.maxZ))){
            if(!level.hasChunkAt(at)||!SceneFluidGeometry.canOccupy(level,at))continue;var fluid=level.getFluidState(at);
            if((fluid.is(FluidTags.WATER)||fluid.is(FluidTags.LAVA))&&area.intersects(new AABB(at.getX(),at.getY(),at.getZ(),at.getX()+1,at.getY()+fluid.getHeight(level,at),at.getZ()+1)))return at.immutable();
        }
        // Hit-capsule feet are only an acquisition hint. The actual root
        // binding/support and tight top-face contact are checked before damage.
        var foot=BlockPos.containing(box.getCenter().x,box.minY-.05,box.getCenter().z);
        if(level.hasChunkAt(foot)&&level.getBlockState(foot).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)&&Math.abs(box.minY-foot.getY()-1)<=.05)return foot;
        return null;
    }
    private static Vec3 rootFeet(WorldMotionWorld.View v,Reply binding,SifuFrame.Vec3 hit){var offset=binding.rootOffset();return new Vec3(v.placement().mcX(hit.x()+offset.x()),v.placement().mcY(hit.z()+offset.z()-binding.rootHalfHeight()),v.placement().mcZ(hit.y()+offset.y()));}
    private static AABB physicalBox(WorldMotionWorld.View v,Reply binding,SifuFrame.Vec3 hit){var feet=rootFeet(v,binding,hit);double r=binding.rootRadius()/100.0;return new AABB(feet.x-r,feet.y,feet.z-r,feet.x+r,feet.y+binding.rootHalfHeight()/50.0,feet.z+r);}
    private static boolean mcSupport(ServerLevel level,AABB box){
        return mcSupport(level,box,.03);
    }
    private static boolean mcSupport(ServerLevel level,AABB box,double depth){
        var foot=new AABB(box.minX+1e-5,box.minY-depth,box.minZ+1e-5,box.maxX-1e-5,box.minY+.001,box.maxZ-1e-5);
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(foot.minX,foot.minY,foot.minZ),BlockPos.containing(foot.maxX,foot.maxY,foot.maxZ)))
            if(net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(net.minecraft.world.phys.shapes.Shapes.create(foot),level.getBlockState(pos).getCollisionShape(level,pos).move(pos.getX(),pos.getY(),pos.getZ()),net.minecraft.world.phys.shapes.BooleanOp.AND))return true;
        return false;
    }
    private static boolean support(WorldMotionWorld.View v,Flight f){
        var box=f.body.getBoundingBox();var feet=new AABB(box.minX+1e-5,box.minY-.002,box.minZ+1e-5,box.maxX-1e-5,box.minY+.001,box.maxZ-1e-5);
        return SifuCollision.withQueryView(v.scene(),()->{for(var shape:f.level.getBlockCollisions(f.body,feet))if(!shape.isEmpty())return true;return false;});
    }
    private static Vec3 normal(Reply reply){int bits=reply.flags();var n=new Vec3((byte)(bits>>>8)/127.0,(byte)(bits>>>24)/127.0,(byte)(bits>>>16)/127.0);return n.lengthSqr()>.8?n.normalize():Vec3.ZERO;}
    private static void landing(WorldMotionWorld.View v,Flight f,double dy,boolean reconcile){
        boolean grounded=support(v,f)||f.nativeGround&&f.groundScene==v.scene()&&f.groundRevision==v.nativeStatus().shapeRevision();
        if(reconcile||f.body.onGround()&&!grounded||!f.body.onGround()&&grounded&&dy<0)f.prediction.reconcileFall(dy,grounded);
        f.body.setOnGround(grounded);var contact=f.prediction.consumeLanding();
        var proxy=EnemyMirrors.hitAttacker(f.key.id(),f.key.spawn());if(grounded&&proxy!=null){
            WorldEffects.landed(proxy,f.scope,contact,f.source);
            WorldEffects.stood(proxy,f.scope,rootFeet(v,f,f.last),f.last.atMs(),f.source);
        }
    }
    public static void machineReleased(ServerLevel level,WorldMotionRequests.Scope scope,EnemyFrame.Key key,Reply binding,Reply release,Vec3 velocity,PistonContacts.Source source){
        machineReleased(level,scope,key,binding,release,velocity,source,0);
    }
    public static void machineReleased(ServerLevel level,WorldMotionRequests.Scope scope,EnemyFrame.Key key,Reply binding,Reply release,Vec3 velocity,PistonContacts.Source source,long playerStamp){
        var v=WorldMotionWorld.current();if(v==null||!scope.equals(v.scope())||release.status()!=3||release.restored()!=(NativeMotionSubjects.player(key)?1:7))return;
        if(NativeMotionSubjects.player(key)&&(v.nativeStatus()==null||(v.nativeStatus().capabilities()&PLAYER_FLUID_CAPABILITY)==0||playerStamp==0||playerStamp!=v.nativeStatus().playerStamp()))return;
        // UE Walking deliberately keeps its own small floor gap. Do not feed
        // that static player separation back into an endless MC settling lease.
        if(NativeMotionSubjects.player(key)&&velocity.lengthSqr()<1e-10)return;
        var box=physicalBox(v,binding,release.position());var support=mcSupport(level,box);
        // Vanilla's upward piston separation leaves a small air gap. Let its
        // original gravity settle that gap after the WHOLE action finishes.
        boolean settling=source.nextProgress()>=1&&support&&!mcSupport(level,box,.002);
        if(support||velocity.lengthSqr()>1e-10)handoffs.put(key,new Handoff(scope,binding,release,velocity,source,support,settling,WorldWriter.clockMillis(),playerStamp));
    }
    public static void tick(MinecraftServer server){
        var level=server.overworld();var view=WorldMotionWorld.current();
        if(view==null||scope!=null&&!scope.equals(view.scope())){stop();if(view==null)return;}
        scope=view.scope();
        for(var f:new ArrayList<>(flights.values()))advance(f);
        if(!valid(view,level)||!currentGeometry(view))return;
        long now=WorldWriter.clockMillis();
        var candidates=new ArrayList<>(view.enemies().entries());
        // Player acquisition currently requires a real mechanical handoff.
        // Water alone never acquires it or reinterprets Native movement input.
        if((view.nativeStatus().capabilities()&PLAYER_FLUID_CAPABILITY)!=0&&handoffs.containsKey(NativeMotionSubjects.PLAYER)){
            var player=NativeMotionSubjects.entry(view,NativeMotionSubjects.PLAYER);if(player!=null)candidates.add(player);
        }
        for(var incoming:candidates){
            var e=NativeMotionSubjects.player(incoming.key())?incoming:PistonMachines.pose(view.enemies(),incoming);
            if(flights.size()>=16||view.requests().pending()>=24)break;
            if(!e.alive()||flights.containsKey(e.key())||BoatPassengers.holds(e.key())||now<retry.getOrDefault(e.key(),0L)||e.radius()<10||e.radius()>100||e.halfHeight()>200)continue;
            var proxy=NativeMotionSubjects.find(e.key());if(proxy==null||proxy.isRemoved())continue;
            var at=medium(level,proxy.getBoundingBox());var h=handoffs.get(e.key());
            var impulse=impulses.get(e.key());
            if(impulse!=null&&(!impulse.scope.equals(view.scope())||now-impulse.at>1000||impulse.at>now)){
                impulses.remove(e.key());impulse=null;
            }
            if(impulse!=null&&e.health()!=impulse.health)continue; // wait for the actual post-damage native snapshot
            if(h!=null){
                var p=e.location();var original=h.release.position();double distance=Math.sqrt(Math.pow(p.x()-original.x(),2)+Math.pow(p.y()-original.y(),2)+Math.pow(p.z()-original.z(),2));
                if(!h.scope.equals(view.scope())||h.release.health()!=e.health()||distance>1||NativeMotionSubjects.player(e.key())&&h.playerStamp!=view.nativeStatus().playerStamp()||(h.settling||h.velocity.lengthSqr()>1e-10)&&now-h.at>500){handoffs.remove(e.key());h=null;}
                else if(!h.settling&&h.velocity.lengthSqr()<1e-10&&(!h.supported||mcSupport(level,physicalBox(view,h.binding,p))))h=null;
            }
            if(NativeMotionSubjects.player(e.key())&&h==null)continue;
            if(at==null&&h==null&&impulse==null)continue;if(at==null)at=BlockPos.containing(proxy.position());
            var f=new Flight(view,level,e,proxy,at,impulse==null?h:null,impulse);handoffs.remove(e.key());impulses.remove(e.key());flights.put(f.key,f);f.deadline=now+500;f.renewAt=now+200;
            send(view,f,RESERVE,e.location(),SifuFrame.Vec3.ZERO,now);
        }
        retry.keySet().removeIf(k->NativeMotionSubjects.player(k)?PlayerSignalContacts.nativeBody()==null:view.enemies().entries().stream().noneMatch(e->e.key().equals(k)));
        handoffs.keySet().removeIf(k->NativeMotionSubjects.player(k)?PlayerSignalContacts.nativeBody()==null:view.enemies().entries().stream().noneMatch(e->e.key().equals(k)&&e.alive()));
        momentum.entrySet().removeIf(e->now>=e.getValue().until||(NativeMotionSubjects.player(e.getKey())?PlayerSignalContacts.nativeBody()==null:view.enemies().entries().stream().noneMatch(x->x.key().equals(e.getKey())&&x.alive())));
    }
    private static Reply result(CompletableFuture<Reply> f){return f==null||!f.isDone()||f.isCompletedExceptionally()||f.isCancelled()?null:f.getNow(null);}
    private static void wake(Flight f){if(!f.queued.compareAndSet(false,true))return;f.level.getServer().execute(()->{f.queued.set(false);if(flights.get(f.key)==f)advance(f);});}
    private static void send(WorldMotionWorld.View v,Flight f,int op,SifuFrame.Vec3 pos,SifuFrame.Vec3 delta,long now){
        if(op!=STEP)f.burst=List.of();
        if(op==RELEASE||op==CANCEL){f.retryOp=0;f.resumeMove=false;}
        f.pendingOp=op;f.pendingDelta=delta;
        if(op==RENEW){f.deadline=now+500;f.renewAt=now+200;}
        f.pending=request(v,f,op,pos,delta,now);
        f.pending.whenComplete((reply,error)->wake(f));
    }
    private static CompletableFuture<Reply> request(WorldMotionWorld.View v,Flight f,int op,SifuFrame.Vec3 pos,SifuFrame.Vec3 delta,long now){
        return v.requests().request(f.scope,id->{if(op==RESERVE)f.lease=id;
            return new Command(op,f.authoritySource,id,op==RESERVE?0:f.lease,now,f.source.getMostSignificantBits(),f.source.getLeastSignificantBits(),f.action,f.level.getGameTime(),
                f.scope.save(),f.scope.map(),v.nativeStatus().shapeRevision(),f.scope.world(),f.key.id(),f.key.spawn(),op==RESERVE||op==RENEW?500:0,pos,delta,
                f.initial.health(),f.initial.radius(),f.initial.halfHeight(),NativeMotionSubjects.player(f.key)?PLAYER:0,f.sourcePos.getX(),f.sourcePos.getY(),f.sourcePos.getZ(),0,0,0,f.stateHash,f.sectionRevision,
                f.binding==null?(f.playerStamp!=0?f.playerStamp:f.actorStamp):f.binding.actorSerial(),f.binding==null?0:f.binding.componentToken());});
    }
    private static Vec3 rootFeet(WorldMotionWorld.View v,Flight f,Reply reply){
        var p=reply.position();var offset=f.binding.rootOffset();
        return new Vec3(v.placement().mcX(p.x()+offset.x()),v.placement().mcY(p.z()+offset.z()-f.binding.rootHalfHeight()),v.placement().mcZ(p.y()+offset.y()));
    }
    private static void applied(WorldMotionWorld.View v,Flight f,Reply reply){
        poses.put(f.key,new Pose(f.scope,reply,WorldWriter.clockMillis()+600));
        var proxy=NativeMotionSubjects.find(f.key);if(proxy==null)return;var p=reply.position();
        proxy.setPos(v.placement().mcX(p.x()),v.placement().mcY(p.z()-f.initial.halfHeight()),v.placement().mcZ(p.y()));
        if(proxy instanceof EnemyProxy enemy)enemy.updateSignals();else if(proxy instanceof PlayerSignalBody player)player.updateSignals();
    }
    private static boolean binding(Flight f,Reply r){return r!=null&&r.actorSerial()==f.binding.actorSerial()&&r.componentToken()==f.binding.componentToken()&&r.health()==f.initial.health();}
    /** Queue handback BEFORE the world damage command. Its H7 reaction then
     * runs under Native control and the final original-Tick receipt stays observable. */
    public static void beforeDamage(EnemyProxy proxy,WorldMotionWorld.View v){
        var f=flights.get(proxy.sifuKey());
        if(f==null||f.binding==null||f.phase==Phase.RELEASE||f.phase==Phase.CANCEL||v==null||v.nativeStatus()==null||!f.scope.equals(v.scope()))return;
        f.damageYield=true;f.phase=Phase.RELEASE;send(v,f,RELEASE,f.last.position(),SifuFrame.Vec3.ZERO,WorldWriter.clockMillis());
    }
    private static void retire(Flight f,boolean ok){
        if(!ok&&logs++<80){var r=result(f.pending);LoggerFactory.getLogger("sifucraft").info("[fluid-motion] released target={}/{} phase={} receipt={} native_binding={} stats={}",f.key.id(),f.key.spawn(),f.phase,r==null?-1:r.status(),f.binding!=null,stats());}
        flights.remove(f.key);retry.merge(f.key,WorldWriter.clockMillis()+(ok?100:500),Math::max);if(f.body!=null)f.body.discard();
        if(!ok)failed++;
    }
    private static void cancel(WorldMotionWorld.View v,Flight f,long now){
        if(f.phase==Phase.CANCEL)return;
        if(logs++<80){var r=result(f.pending);String error=f.pending==null?"none":f.pending.handle((value,failure)->failure==null?"":failure.toString()).getNow("pending");
            LoggerFactory.getLogger("sifucraft").info("[fluid-motion] cancel target={} phase={} op={} now={} deadline={} available={} same_scope={} flags={} caps={} reply={} error={} caller={}",f.key,f.phase,f.pendingOp,now,f.deadline,available(v,f.level),v!=null&&f.scope.equals(v.scope()),v==null||v.nativeStatus()==null?-1:v.nativeStatus().flags(),v==null||v.nativeStatus()==null?-1:v.nativeStatus().capabilities(),r==null?-1:r.status(),error,Thread.currentThread().getStackTrace()[2].getLineNumber());
        }f.phase=Phase.CANCEL;
        if(f.binding!=null&&v!=null&&f.scope.equals(v.requests().scope())&&v.nativeStatus()!=null)send(v,f,CANCEL,f.last.position(),SifuFrame.Vec3.ZERO,now);
        else retire(f,false); // an unobserved reservation remains bounded by its native 500ms deadline
    }
    private static void advance(Flight f){
        var v=WorldMotionWorld.current();long now=WorldWriter.clockMillis();
        if(f.phase==Phase.CANCEL){if(f.pending==null||f.pending.isDone()||now>f.deadline+1000)retire(f,false);return;}
        if(!sameWorld(v,f.level)||!f.scope.equals(v.scope())||now>=f.deadline+(f.phase==Phase.RELEASE?1000:0)){cancel(v,f,now);return;}
        var proxy=NativeMotionSubjects.find(f.key);
        boolean yieldingPlayer=NativeMotionSubjects.player(f.key)&&proxy==null&&v.nativeStatus()!=null&&v.nativeStatus().playerStamp()==f.playerStamp;
        if(yieldingPlayer&&f.binding!=null&&f.phase!=Phase.CANCEL){
            f.playerYield=true;
            if(f.phase!=Phase.RELEASE){f.phase=Phase.RELEASE;send(v,f,RELEASE,f.last.position(),SifuFrame.Vec3.ZERO,now);return;}
        }
        if((proxy==null||proxy.isRemoved()||!proxy.isAlive())&&!(yieldingPlayer&&f.playerYield&&f.phase==Phase.RELEASE)){cancel(v,f,now);return;}
        // A delayed render snapshot is not a new world or a new actor. Issue no
        // commands until fresh, while retaining only the existing hard lease.
        // A seqlock writer can also make a single status read unavailable.
        if(v.nativeStatus()==null)return;
        if(NativeMotionSubjects.player(f.key)&&v.nativeStatus().playerStamp()!=f.playerStamp){cancel(v,f,now);return;}
        if(v.nativeStatus().flags()!=0){cancel(v,f,now);return;}
        if(!fresh(v))return;
        if(f.pending!=null&&!f.pending.isDone())return;
        // A rejected collision epoch has performed no sweep. Keep the existing
        // short authority while the new geometry ACK arrives, then issue a NEW
        // command against that epoch. Never replay a partial/applied movement.
        var rejection=result(f.pending);
        if(f.burst.isEmpty()&&f.binding!=null&&binding(f,rejection)&&(rejection.status()==8||rejection.status()==9)){
            // This command performed no movement. Preserve the unfinished
            // original physics tick (including velocity/fall bookkeeping), and
            // try only its unexecuted segment against the NEW collision epoch.
            // A real new blocker is resolved by that epoch's authoritative sweep.
            f.retryOp=f.pendingOp;f.pending=null;
        }
        if(f.retryOp!=0){
            if(f.retryOp==STEP&&now>=f.renewAt){f.retryOp=0;f.resumeMove=true;f.phase=Phase.RENEW;send(v,f,RENEW,f.last.position(),SifuFrame.Vec3.ZERO,now);return;}
            if(f.retryOp!=RENEW&&(!v.shapesCurrent()||!valid(v,f.level)))return;int op=f.retryOp;f.retryOp=0;
            if(op==STEP)sendPart(v,f,now);else send(v,f,op,f.last.position(),f.pendingDelta,now);return;
        }
        if(f.phase==Phase.ACQUIRE){
            var r=result(f.pending);if(r==null||r.status()!=1||r.actorSerial()<=0||r.rootRadius()<10||r.rootHalfHeight()<r.rootRadius()){retire(f,false);return;}
            f.binding=f.last=r;f.pending=null;
            long seed=((long)f.key.id()<<32)^f.key.spawn();
            f.body=NativeMotionSubjects.player(f.key)?new VanillaPlayerBody(f.level,r.rootRadius(),r.rootHalfHeight(),seed):new VanillaFluidBody(f.level,r.rootRadius(),r.rootHalfHeight(),seed,true);
            f.prediction=(MotionPrediction)f.body;if(NativeMotionSubjects.player(f.key)){f.body.setYRot(f.initial.yaw()-90);f.body.setXRot(0);}
            f.body.setPos(rootFeet(v,f,r));f.body.setDeltaMovement(f.initialVelocity);
            if(f.machine)f.body.setOnGround(support(v,f));
            f.phase=Phase.IDLE;acquired++;
            var listener=observer;if(listener!=null)listener.prepared(sample(f));
        }
        if(f.phase==Phase.RENEW){
            var r=result(f.pending);if(!binding(f,r)||r.status()!=2){cancel(v,f,now);return;}f.last=r;applied(v,f,r);f.pending=null;renewed++;
            if(f.resumeMove){f.resumeMove=false;f.phase=Phase.MOVE;
                if(v.shapesCurrent()&&valid(v,f.level))sendPart(v,f,now);else{f.retryOp=STEP;f.pendingDelta=partDelta(f);}return;
            }f.phase=Phase.IDLE;
        }
        if(f.phase==Phase.RELEASE){
            var r=result(f.pending);boolean identity=r!=null&&r.actorSerial()==f.binding.actorSerial()&&r.componentToken()==f.binding.componentToken();
            boolean ok=identity&&r.restored()==(NativeMotionSubjects.player(f.key)?1:7)&&(r.status()==3||f.damageYield&&(r.status()==12||r.status()==6));
            if(ok){if(r.status()==3){if(!f.playerYield)applied(v,f,r);if(f.machine&&!f.damageYield&&!f.machineYield&&!f.playerYield)machineReleased(f.level,f.scope,f.key,f.binding,r,Vec3.ZERO,f.machineSource,f.playerStamp);}released++;}retire(f,ok);return;
        }
        if(f.phase==Phase.MOVE){
            if(f.burst.isEmpty()){
                if(now>=f.renewAt){f.resumeMove=true;f.phase=Phase.RENEW;send(v,f,RENEW,f.last.position(),SifuFrame.Vec3.ZERO,now);}else sendPart(v,f,now);return;
            }
            int completed=0;boolean rejected=false;
            for(var future:f.burst){
                var r=result(future);if(!binding(f,r)||(r.status()!=2&&r.status()!=4&&r.status()!=5&&r.status()!=8&&r.status()!=9)){cancel(v,f,now);return;}
                f.last=r;applied(v,f,r);
                if(r.status()==8||r.status()==9){rejected=true;continue;}
                if(proxy instanceof EnemyProxy enemy)WorldEffects.moved(enemy,f.scope,v.placement(),f.initial.halfHeight(),r);
                steps++;completed++;
                if(r.status()!=2){blocked++;f.partial=true;f.nativeGround=(r.flags()&4)!=0;f.contactNormal=normal(r);}
            }
            f.burst=List.of();f.pending=null;f.part=f.partial?f.parts:f.part+completed;
            if(rejected&&!f.partial&&f.part<f.parts){f.retryOp=STEP;f.pendingDelta=partDelta(f);return;}
            if(f.part<f.parts){
                if(now>=f.renewAt){f.resumeMove=true;f.phase=Phase.RENEW;send(v,f,RENEW,f.last.position(),SifuFrame.Vec3.ZERO,now);}else sendPart(v,f,now);
                return;
            }
            var actual=rootFeet(v,f,f.last);var moved=actual.subtract(f.actualStart);var velocity=f.nextVelocity;
            if(f.partial&&f.contactNormal.lengthSqr()>.8){
                // Entity.move already clipped known MC contacts; subsequent
                // bubble/fluid forces belong to the NEXT tick. Only reconcile
                // axes whose blocker was absent from the original prediction.
                var n=new Vec3(f.prediction.clipped(1)?0:f.contactNormal.x,f.prediction.clipped(2)?0:f.contactNormal.y,f.prediction.clipped(4)?0:f.contactNormal.z);
                double length=n.lengthSqr(),into=velocity.dot(n);if(length>1e-10&&into<0)velocity=velocity.subtract(n.scale(into/length));
            }
            else{
                if(Math.abs(moved.x-f.requested.x)>1e-4&&!f.prediction.clipped(1))velocity=new Vec3(0,velocity.y,velocity.z);
                if(Math.abs(moved.y-f.requested.y)>1e-4&&!f.prediction.clipped(2))velocity=new Vec3(velocity.x,0,velocity.z);
                if(Math.abs(moved.z-f.requested.z)>1e-4&&!f.prediction.clipped(4))velocity=new Vec3(velocity.x,velocity.y,0);
            }
            f.body.setPos(actual);f.body.setDeltaMovement(velocity);f.phase=Phase.IDLE;landing(v,f,moved.y,f.partial);
            var listener=observer;if(listener!=null)listener.completed(sample(f));
            if(f.phase==Phase.RELEASE)return;
            if(f.machinePending){f.machinePending=false;f.yieldUntilTick=f.level.getGameTime()+1;return;}
        }
        if(f.phase!=Phase.IDLE)return;
        if(now>=f.renewAt){f.phase=Phase.RENEW;send(v,f,RENEW,f.last.position(),SifuFrame.Vec3.ZERO,now);return;}
        if(!v.shapesCurrent()||!valid(v,f.level))return;
        long tick=f.level.getGameTime();if(tick==f.lastTick)return;f.lastTick=tick;
        if(tick<=f.yieldUntilTick)return;
        if(f.body.onGround()&&!support(v,f)&&!(f.nativeGround&&f.groundScene==v.scene()&&f.groundRevision==v.nativeStatus().shapeRevision()))f.body.setOnGround(false);
        if(medium(f.level,f.body.getBoundingBox())==null&&f.body.onGround()&&f.body.getDeltaMovement().horizontalDistance()<.003&&f.body.getDeltaMovement().y<=0){
            f.phase=Phase.RELEASE;send(v,f,RELEASE,f.last.position(),SifuFrame.Vec3.ZERO,now);return;
        }
        f.actualStart=rootFeet(v,f,f.last);f.body.setPos(f.actualStart);
        f.partial=f.nativeGround=false;f.contactNormal=Vec3.ZERO;f.groundScene=v.scene();f.groundRevision=v.nativeStatus().shapeRevision();f.prediction.beginPrediction();
        var listener=observer;if(listener!=null){if(f.body instanceof VanillaFluidBody mob)listener.beforeSimulation(sample(f),mob);}
        // This body is unregistered, so the world does not call commonTick for it.
        // Use the installed server entry to retain counters/old pose/AI cadence.
        SifuCollision.withQueryView(v.scene(),()->{f.level.tickNonPassenger(f.body);return null;});
        if(listener!=null)listener.simulated(sample(f));
        f.requested=f.body.position().subtract(f.actualStart);f.nextVelocity=f.body.getDeltaMovement();
        if(!Double.isFinite(f.requested.lengthSqr())||f.requested.length()>4.1){cancel(v,f,now);return;}
        if(f.requested.lengthSqr()<1e-12){f.body.setPos(f.actualStart);landing(v,f,0,false);if(listener!=null)listener.completed(sample(f));return;}
        f.parts=(int)Math.ceil(f.requested.length()/.1);f.part=0;f.phase=Phase.MOVE;sendPart(v,f,now);
    }
    private static void sendPart(WorldMotionWorld.View v,Flight f,long now){
        // Reserve eight broker slots for heartbeat/release/damage. Native still
        // validates and sweeps every <=10cm command separately and in order.
        int count=Math.min(Math.min(8,f.parts-f.part),24-v.requests().pending());if(count<=0){f.pending=null;return;}
        var delta=partDelta(f);var position=f.last.position();var batch=new ArrayList<CompletableFuture<Reply>>(count);
        for(int i=0;i<count;i++){
            batch.add(request(v,f,STEP,position,delta,now));position=new SifuFrame.Vec3(position.x()+delta.x(),position.y()+delta.y(),position.z()+delta.z());
        }
        f.burst=List.copyOf(batch);f.pendingOp=STEP;f.pendingDelta=delta;
        f.pending=CompletableFuture.allOf(batch.toArray(new CompletableFuture<?>[0])).thenApply(ignored->batch.get(batch.size()-1).getNow(null));
        f.pending.whenComplete((reply,error)->wake(f));
    }
    private static SifuFrame.Vec3 partDelta(Flight f){
        var d=f.requested.scale(100.0/f.parts);
        return new SifuFrame.Vec3((float)d.x,(float)d.z,(float)d.y);
    }
    /** A delayed main snapshot must never overwrite a newer physical receipt. */
    public static EnemyFrame.Entry pose(EnemyFrame frame,EnemyFrame.Entry e){
        var p=poses.get(e.key());if(p==null)return e;
        if(p.scope.nativeSession()!=frame.session()||p.scope.world()!=frame.worldGeneration()||WorldWriter.clockMillis()>p.until||frame.frame()>p.reply.nativeFrame()){poses.remove(e.key());return e;}
        return new EnemyFrame.Entry(e.id(),e.spawn(),p.reply.position(),e.yaw(),e.radius(),e.halfHeight(),e.health(),e.maxHealth(),e.flags(),e.faction(),e.velocity());
    }
    public static SifuFrame.Vec3 playerPose(WorldMotionWorld.View view,SifuFrame frame,SifuFrame.Vec3 position){
        var p=poses.get(NativeMotionSubjects.PLAYER);if(p==null)return position;
        if(view==null||!p.scope.equals(view.scope())||frame.worldGeneration()!=p.scope.world()||WorldWriter.clockMillis()>p.until||frame.frame()>p.reply.nativeFrame()){
            poses.remove(NativeMotionSubjects.PLAYER);return position;
        }
        return p.reply.position();
    }
    public static void playerGone(){poses.remove(NativeMotionSubjects.PLAYER);handoffs.remove(NativeMotionSubjects.PLAYER);momentum.remove(NativeMotionSubjects.PLAYER);retry.remove(NativeMotionSubjects.PLAYER);}
    public static void stop(){for(var f:flights.values())if(f.body!=null)f.body.discard();flights.clear();retry.clear();poses.clear();handoffs.clear();impulses.clear();momentum.clear();scope=null;}
}
