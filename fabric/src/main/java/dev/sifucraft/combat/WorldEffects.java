package dev.sifucraft.combat;

import static dev.sifucraft.bridge.WorldMotionProtocol.*;
import dev.sifucraft.blocks.BlockStateKey;
import dev.sifucraft.blocks.PistonMovePlans;
import dev.sifucraft.blocks.SceneFluidGeometry;
import dev.sifucraft.blocks.WorldBlocks;
import dev.sifucraft.blocks.WorldMotionWorld;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.bridge.WorldWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import org.slf4j.LoggerFactory;

/** Only actual vanilla environment hurt callbacks can issue a world-scoped damage event. */
public final class WorldEffects {
    private WorldEffects(){}
    private record Evidence(String type,BlockPos pos,MachineProjectiles.Origin machine){Evidence(String type,BlockPos pos){this(type,pos,null);}}
    private static long sent,applied,rejected,lost;private static int logged;
    public record Stats(long sent,long applied,long rejected,long lost){}
    public static Stats stats(){return new Stats(sent,applied,rejected,lost);}
    /** NativeFree has no vanilla Entity.move block effects (noPhysics). Invoke
     * only the installed fluid/fire callbacks at its actual snapshot body, using
     * vanilla's own ordered/deduplicated effect collector. Never run portals or
     * unrelated block actions through this observation path. */
    public static void touch(EnemyProxy proxy){
        if(view(proxy)==null)return;
        var level=proxy.level();var box=proxy.getBoundingBox().deflate(1e-5);var collector=new InsideBlockEffectApplier.StepBasedCollector();collector.advanceStep(0);
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ))){
            if(!level.hasChunkAt(pos))continue;var state=level.getBlockState(pos);var fluid=state.getFluidState();
            if((fluid.is(FluidTags.WATER)||fluid.is(FluidTags.LAVA))&&SceneFluidGeometry.canOccupy(level,pos)&&
                box.intersects(new AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+fluid.getHeight(level,pos),pos.getZ()+1)))fluid.entityInside(level,pos,proxy,collector);
            if((state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE))&&SceneFluidGeometry.canOccupy(level,pos)&&
                Shapes.joinIsNotEmpty(Shapes.create(box),state.getEntityInsideCollisionShape(level,pos,proxy).move(pos.getX(),pos.getY(),pos.getZ()),BooleanOp.AND))state.entityInside(level,pos,proxy,collector,false);
        }
        collector.applyAndClear(proxy);
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
        var machine=MachineProjectiles.evidence(proxy,source);
        if(machine!=null)return new Evidence(source.typeHolder().unwrapKey().orElseThrow().identifier().toString(),machine.pos(),machine);
        if(source.getEntity()!=null||source.getDirectEntity()!=null)return null; // machine projectiles need their separate origin credential
        var level=proxy.level();var box=proxy.getBoundingBox();
        if(source.is(DamageTypes.ON_FIRE))return proxy.getRemainingFireTicks()>0?new Evidence("minecraft:on_fire",proxy.blockPosition()):null;
        if(source.is(DamageTypes.DROWN)){
            var pos=BlockPos.containing(proxy.getX(),proxy.getEyeY(),proxy.getZ());
            return proxy.isEyeInFluid(FluidTags.WATER)&&proxy.getAirSupply()<=0&&level.getFluidState(pos).is(FluidTags.WATER)&&SceneFluidGeometry.canOccupy(level,pos)?new Evidence("minecraft:drown",pos):null;
        }
        if(source.is(DamageTypes.HOT_FLOOR)){
            var pos=BlockPos.containing(proxy.getX(),box.minY-.01,proxy.getZ());
            return level.getBlockState(pos).is(Blocks.MAGMA_BLOCK)&&Math.abs(box.minY-pos.getY()-1)<.05?new Evidence("minecraft:hot_floor",pos):null;
        }
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
        var pos=proof.pos;var section=WorldBlocks.index().snapshot(SectionPos.of(pos).asLong());
        long state=ShapeProtocol.mapHash(BlockStateKey.encode(proxy.level().getBlockState(pos)));if(state==0)state=1;
        long revision=proof.machine==null?(section==null?0:section.revision()):proof.machine.revision(),stateKey=proof.machine==null?state:proof.machine.state(),now=WorldWriter.clockMillis();var origin=proof.machine==null?proxy.getUUID():proof.machine.action();sent++;
        view.requests().request(view.scope(),id->new Command(DAMAGE,WORLD_EFFECT,id,0,now,origin.getMostSignificantBits(),origin.getLeastSignificantBits(),ShapeProtocol.mapHash(proof.type),proxy.level().getGameTime(),
            view.scope().save(),view.scope().map(),view.nativeStatus().shapeRevision(),view.scope().world(),entry.id(),entry.spawn(),0,entry.location(),dev.sifucraft.bridge.SifuFrame.Vec3.ZERO,
            entry.health(),entry.radius(),entry.halfHeight(),0,pos.getX(),pos.getY(),pos.getZ(),0,0,amount,stateKey,revision,0,0))
            .whenComplete((reply,error)->proxy.level().getServer().execute(()->{
                if(error!=null)lost++;else if(reply.status()==2)applied++;else rejected++;
                if(logged++<80)LoggerFactory.getLogger("sifucraft").info("[world-effects] type={} target={}/{} mc_final={} receipt={} native_HP={} error={}",proof.type,entry.id(),entry.spawn(),amount,reply==null?-1:reply.status(),reply==null?-1:reply.health(),error==null?"":error.toString());
            }));
    }
}
