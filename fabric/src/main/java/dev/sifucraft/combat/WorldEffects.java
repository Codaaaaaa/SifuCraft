package dev.sifucraft.combat;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.PistonMovePlans;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.WorldWriter;
import dev.sifucraft.bridge.WorldMotionRequests;
import dev.sifucraft.collision.MapPlacement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.slf4j.LoggerFactory;

/** Only actual vanilla environment hurt callbacks can issue a world-scoped damage event. */
public final class WorldEffects {
    private WorldEffects(){}
    private record Evidence(String type,BlockPos pos,MachineProjectiles.Origin machine,java.util.UUID causal){Evidence(String type,BlockPos pos){this(type,pos,null,null);}Evidence(String type,BlockPos pos,MachineProjectiles.Origin machine){this(type,pos,machine,null);}}
    private record LandingProof(EnemyProxy target,dev.sifucraft.bridge.WorldMotionRequests.Scope scope,BlockPos pos,java.util.UUID source){}
    private static final ThreadLocal<LandingProof> landing=new ThreadLocal<>();
    private record FloorTick(WorldMotionRequests.Scope scope,long tick){}
    private static final Map<EnemyProxy,FloorTick> floors=new WeakHashMap<>();
    /** A real root/support result, never the larger hit capsule or prediction,
     * authorizes precisely one original hot-floor callback per world tick. */
    static void stood(EnemyProxy target,WorldMotionRequests.Scope scope,net.minecraft.world.phys.Vec3 root,long at,java.util.UUID source){
        var view=view(target);long now=WorldWriter.clockMillis();
        if(view==null||!scope.equals(view.scope())||!FluidMotion.currentGeometry(view)||at>now||now-at>200||!Double.isFinite(root.x)||!Double.isFinite(root.y)||!Double.isFinite(root.z))return;
        var pos=BlockPos.containing(root.x,root.y-.2,root.z);var state=target.level().getBlockState(pos);
        if(!state.is(Blocks.MAGMA_BLOCK)||Math.abs(root.y-pos.getY()-1)>.0025)return;
        var tick=new FloorTick(scope,target.level().getGameTime());if(tick.equals(floors.put(target,tick)))return;
        var previous=landing.get();landing.set(new LandingProof(target,scope,pos,source));
        try{state.getBlock().stepOn(target.level(),pos,state,target);}
        finally{if(previous==null)landing.remove();else landing.set(previous);}
    }
    private record TimedMovement(BlockEnvironment.Movement movement,long at){}
    private record ContactPath(WorldMotionRequests.Scope scope,List<TimedMovement> movements){}
    private record PathProof(EnemyProxy target,WorldMotionRequests.Scope scope,Map<String,BlockEnvironment.Contact> contacts){}
    private static final Map<EnemyProxy,ContactPath> paths=new WeakHashMap<>(); // server thread; removed activations are not retained
    private static final ThreadLocal<PathProof> pathProof=new ThreadLocal<>();
    /** The caller has reconciled an actual physical endpoint. Vanilla resolves
     * the landing block, fall multiplier, armor, effects and hurt immunity. */
    public static void landed(EnemyProxy target,dev.sifucraft.bridge.WorldMotionRequests.Scope scope,VanillaFluidBody.Landing contact,java.util.UUID source){
        if(contact==null||contact.distance()<=0)return;var v=WorldMotionWorld.current();if(v==null||!scope.equals(v.scope()))return;
        var previous=landing.get();landing.set(new LandingProof(target,scope,contact.pos(),source));
        try{var state=target.level().getBlockState(contact.pos());state.getBlock().fallOn(target.level(),state,contact.pos(),target,contact.distance());}
        finally{if(previous==null)landing.remove();else landing.set(previous);}
    }
    private static long sent,applied,rejected,lost,pathSegments,pathDropped;private static int logged,pathLogs;
    public record Stats(long sent,long applied,long rejected,long lost,long pathSegments,long pathDropped){}
    public static Stats stats(){return new Stats(sent,applied,rejected,lost,pathSegments,pathDropped);}
    public static void moved(EnemyProxy target,WorldMotionRequests.Scope scope,MapPlacement placement,float halfHeight,Reply reply){
        if(!(target.level() instanceof ServerLevel))return;
        if(reply.target()!=target.sifuKey().id()||reply.spawn()!=target.sifuKey().spawn()||reply.world()!=scope.world()||
            (reply.status()!=2&&reply.status()!=4&&reply.status()!=5)||reply.actorSerial()<=0||reply.componentToken()==0)return;
        var p=reply.position();var d=reply.delta();
        var from=new net.minecraft.world.phys.Vec3(placement.mcX(p.x()-d.x()),placement.mcY(p.z()-d.z()-halfHeight),placement.mcZ(p.y()-d.y()));
        var to=new net.minecraft.world.phys.Vec3(placement.mcX(p.x()),placement.mcY(p.z()-halfHeight),placement.mcZ(p.y()));
        if(from.distanceToSqr(to)>.101*.101){pathDropped++;return;}
        receipted(target,scope,new BlockEnvironment.Movement(from,to),reply.atMs());
    }
    /** Only the verified STEP caller supplies actual hit-capsule feet, never a prediction. */
    static void receipted(EnemyProxy target,WorldMotionRequests.Scope scope,BlockEnvironment.Movement movement,long at){
        if(!(target.level() instanceof ServerLevel))return;
        if(movement.from().distanceToSqr(movement.to())<1e-12)return;
        var path=paths.get(target);
        if(path==null||!path.scope.equals(scope)){if(path!=null)pathDropped+=path.movements.size();path=new ContactPath(scope,new ArrayList<>());paths.put(target,path);}
        path.movements.add(new TimedMovement(movement,at));pathSegments++;
    }
    /** NativeFree has no vanilla Entity.move block effects (noPhysics). Invoke
     * only the installed fluid/fire callbacks at its actual snapshot body, using
     * vanilla's own ordered/deduplicated effect collector. Never run portals or
     * unrelated block actions through this observation path. */
    public static void touch(EnemyProxy proxy){
        // Client and server copies compare equal by entity id. The client copy
        // must never remove the server's pending physical contact path.
        if(!(proxy.level() instanceof ServerLevel))return;
        var path=paths.remove(proxy);var view=view(proxy);
        if(view==null){
            // A single unavailable seqlock snapshot carries no new authority,
            // but must not discard a still-fresh actual sweep before the next tick.
            var current=WorldMotionWorld.current();long now=WorldWriter.clockMillis();
            if(path!=null&&current!=null&&current.nativeStatus()==null&&path.scope.equals(current.scope())&&
                current.scope().equals(current.requests().scope())&&current.scope().save()==PistonMovePlans.saveKey((ServerLevel)proxy.level())&&
                !proxy.isRemoved()&&proxy.isAlive()&&EnemyMirrors.hitAttacker(proxy.sifuKey().id(),proxy.sifuKey().spawn())==proxy&&
                path.movements.stream().allMatch(p->p.at<=now&&now-p.at<=200))paths.put(proxy,path);
            else if(path!=null){pathDropped+=path.movements.size();unavailable(proxy,path);}return;
        }
        var movements=new ArrayList<BlockEnvironment.Movement>();long now=WorldWriter.clockMillis();
        if(path!=null)for(var timed:path.movements){
            if(!path.scope.equals(view.scope())||timed.at>now||now-timed.at>200){pathDropped++;if(pathLogs++<8)LoggerFactory.getLogger("sifucraft").info("[world-contacts] rejected path target={} age={} same_scope={}",proxy.sifuKey(),now-timed.at,path.scope.equals(view.scope()));continue;}
            movements.add(timed.movement);
        }
        movements.add(new BlockEnvironment.Movement(proxy.position(),proxy.position()));
        var proof=new PathProof(proxy,view.scope(),new HashMap<>());var previous=pathProof.get();pathProof.set(proof);
        try{BlockEnvironment.apply(proxy,movements,contact->proof.contacts.putIfAbsent(contact.type(),contact));}
        finally{if(previous==null)pathProof.remove();else pathProof.set(previous);}
    }
    private static void unavailable(EnemyProxy proxy,ContactPath path){
        if(pathLogs++>=8)return;
        var v=WorldMotionWorld.current();var status=v==null?null:v.nativeStatus();long clock=WorldWriter.clockMillis();
        LoggerFactory.getLogger("sifucraft").info("[world-contacts] unavailable target={} segments={} native_age={} enemy_age={} flags={} caps={} pending={} same_scope={} proxy_current={} alive={} removed={}",
            proxy.sifuKey(),path.movements.size(),status==null?-1:clock-status.atMs(),v==null||v.enemies()==null?-1:System.currentTimeMillis()-v.enemies().receivedMs(),
            status==null?-1:status.flags(),status==null?-1:status.capabilities(),v==null?-1:v.requests().pending(),v!=null&&path.scope.equals(v.scope()),
            EnemyMirrors.hitAttacker(proxy.sifuKey().id(),proxy.sifuKey().spawn())==proxy,proxy.isAlive(),proxy.isRemoved());
    }
    private static WorldMotionWorld.View view(EnemyProxy proxy){
        if(!(proxy.level() instanceof ServerLevel level)||level.dimension()!=Level.OVERWORLD||proxy.isRemoved()||!proxy.isAlive())return null;
        var view=WorldMotionWorld.current();if(!WorldBlocks.ENABLED||view==null||!view.scope().equals(view.requests().scope())||view.scope().save()!=PistonMovePlans.saveKey(level)||
            view.scene()==null||!view.scene().matchesScope(view.scope().nativeSession(),view.scope().world())||!view.placement().equals(view.scene().placement())||
            view.enemies()==null||view.enemies().session()!=view.scope().nativeSession()||view.enemies().worldGeneration()!=view.scope().world()||
            view.nativeStatus()==null||(view.nativeStatus().capabilities()&DAMAGE_CAPABILITY)==0||view.nativeStatus().flags()!=0||view.requests().pending()>=31||
            EnemyMirrors.hitAttacker(proxy.sifuKey().id(),proxy.sifuKey().spawn())!=proxy)return null;
        long now=WorldWriter.clockMillis(),age=System.currentTimeMillis()-view.enemies().receivedMs();
        if(view.nativeStatus().atMs()>now||now-view.nativeStatus().atMs()>200||age<0||age>200)return null;
        var entry=view.enemies().entries().stream().filter(e->e.key().equals(proxy.sifuKey())&&e.alive()).findFirst().orElse(null);
        if(entry==null||entry.radius()<10||entry.radius()>100||entry.halfHeight()>200)return null;
        return view;
    }
    private static Evidence evidence(EnemyProxy proxy,DamageSource source){
        var blast=SceneExplosions.proof(proxy,source);
        if(blast!=null)return new Evidence(source.typeHolder().unwrapKey().orElseThrow().identifier().toString(),blast.center(),null,blast.event());
        var floor=landing.get();
        if(source.is(DamageTypes.FALL)&&floor!=null&&floor.target==proxy&&WorldMotionWorld.current()!=null&&floor.scope.equals(WorldMotionWorld.current().scope()))return new Evidence("minecraft:fall",floor.pos,null,floor.source);
        var machine=MachineProjectiles.evidence(proxy,source);
        if(machine!=null)return new Evidence(source.typeHolder().unwrapKey().orElseThrow().identifier().toString(),machine.pos(),machine);
        if(source.getEntity()!=null||source.getDirectEntity()!=null)return null; // machine projectiles need their separate origin credential
        var path=pathProof.get();
        if(path!=null&&path.target==proxy&&WorldMotionWorld.current()!=null&&path.scope.equals(WorldMotionWorld.current().scope())){
            String type=source.is(DamageTypes.LAVA)?"minecraft:lava":source.is(DamageTypes.IN_FIRE)?"minecraft:in_fire":null;
            var contact=path.contacts.get(type);
            if(contact!=null&&proxy.level().getBlockState(contact.pos())==contact.state())return new Evidence(contact.type(),contact.pos());
        }
        var level=proxy.level();var box=proxy.getBoundingBox();
        if(source.is(DamageTypes.ON_FIRE))return proxy.getRemainingFireTicks()>0?new Evidence("minecraft:on_fire",proxy.blockPosition()):null;
        if(source.is(DamageTypes.DROWN)){
            var pos=BlockPos.containing(proxy.getX(),proxy.getEyeY(),proxy.getZ());
            return proxy.isEyeInFluid(FluidTags.WATER)&&proxy.getAirSupply()<=0&&level.getFluidState(pos).is(FluidTags.WATER)&&SceneFluidGeometry.canOccupy(level,pos)?new Evidence("minecraft:drown",pos):null;
        }
        if(source.is(DamageTypes.HOT_FLOOR))return floor!=null&&floor.target==proxy&&WorldMotionWorld.current()!=null&&floor.scope.equals(WorldMotionWorld.current().scope())&&level.getBlockState(floor.pos).is(Blocks.MAGMA_BLOCK)?new Evidence("minecraft:hot_floor",floor.pos,null,floor.source):null;
        if(source.is(DamageTypes.LAVA)||source.is(DamageTypes.IN_FIRE)){
            var area=box.deflate(1e-6);
            for(var at:BlockPos.betweenClosed(BlockPos.containing(area.minX,area.minY,area.minZ),BlockPos.containing(area.maxX,area.maxY,area.maxZ))){
                if(!level.hasChunkAt(at)||!SceneFluidGeometry.canOccupy(level,at))continue;
                var state=level.getBlockState(at);
                if(source.is(DamageTypes.IN_FIRE)&&(state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)))return new Evidence("minecraft:in_fire",at.immutable());
                var fluid=state.getFluidState();
                if(source.is(DamageTypes.LAVA)&&fluid.is(FluidTags.LAVA)&&area.intersects(new AABB(at.getX(),at.getY(),at.getZ(),at.getX()+1,at.getY()+fluid.getHeight(level,at),at.getZ()+1)))return new Evidence("minecraft:lava",at.immutable());
            }
        }
        // Fall and crush require actual external-motion receipts. A NativeFree
        // mirror must not duplicate Sifu's own fall damage or invent suffocation.
        return null;
    }
    public static boolean canCapture(EnemyProxy proxy,DamageSource source){return view(proxy)!=null&&evidence(proxy,source)!=null;}
    public static void record(EnemyProxy proxy,DamageSource source,float amount){
        var view=view(proxy);var proof=evidence(proxy,source);if(view==null||proof==null||!Float.isFinite(amount)||amount<=0)return;
        var entry=view.enemies().entries().stream().filter(e->e.key().equals(proxy.sifuKey())&&e.alive()).findFirst().orElse(null);if(entry==null||entry.radius()<10||entry.radius()>100||entry.halfHeight()>200)return;
        FluidMotion.beforeDamage(proxy,view);
        var pos=proof.pos;var section=WorldBlocks.index().snapshot(SectionPos.of(pos).asLong());
        long state=ShapeProtocol.mapHash(BlockStateKey.encode(proxy.level().getBlockState(pos)));if(state==0)state=1;
        long revision=proof.machine==null?(section==null?0:section.revision()):proof.machine.revision(),stateKey=proof.machine==null?state:proof.machine.state(),now=WorldWriter.clockMillis();var origin=proof.causal!=null?proof.causal:proof.machine==null?proxy.getUUID():proof.machine.action();sent++;
        var future=view.requests().request(view.scope(),id->new Command(DAMAGE,WORLD_EFFECT,id,0,now,origin.getMostSignificantBits(),origin.getLeastSignificantBits(),ShapeProtocol.mapHash(proof.type),proxy.level().getGameTime(),
            view.scope().save(),view.scope().map(),view.nativeStatus().shapeRevision(),view.scope().world(),entry.id(),entry.spawn(),0,entry.location(),dev.sifucraft.bridge.SifuFrame.Vec3.ZERO,
            entry.health(),entry.radius(),entry.halfHeight(),0,pos.getX(),pos.getY(),pos.getZ(),0,0,amount,stateKey,revision,0,0))
            .whenComplete((reply,error)->proxy.level().getServer().execute(()->{
                if(error!=null)lost++;else if(reply.status()==2)applied++;else rejected++;
                if(logged++<80)LoggerFactory.getLogger("sifucraft").info("[world-effects] type={} target={}/{} mc_final={} receipt={} native_HP={} error={}",proof.type,entry.id(),entry.spawn(),amount,reply==null?-1:reply.status(),reply==null?-1:reply.health(),error==null?"":error.toString());
            }));
        SceneExplosions.damageSubmitted(proxy,source,future);
    }
}
