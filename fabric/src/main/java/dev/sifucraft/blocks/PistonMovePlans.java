package dev.sifucraft.blocks;

import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.collision.MapPlacement;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.combat.EnemyProxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** MC-side proposal/validation only. Taking a plan books its limit; it never sends or executes Native motion. */
public final class PistonMovePlans {
    public record Scope(long mcSession,long nativeSession,int world,long save,MapPlacement placement){
        public Scope {
            if(mcSession==0||nativeSession==0||world==0||save==0||placement==null||placement.map().isBlank())throw new IllegalArgumentException("piston scope");
        }
    }
    public record View(Scope scope,EnemyFrame enemies,SifuCollision.Active scene){}
    public enum Status { PLANNED,TAKEN,CANCELLED,BUSY,USED,CONTEXT,SOURCE,INDEX,TARGET,REQUEST,CHANGED }
    public record Attempt(Status status,Plan plan){}
    private record Target(UUID uuid,EnemyFrame.Key key){}
    public static final class Plan {
        private final PistonMovePlans owner;
        private final Scope scope;
        private final PistonContacts.Source source;
        private final long ordinal,tick,indexRevision,enemyFrame;
        private final PistonMovingBlockEntity piston;
        private final Entity entity;
        private final Target target;
        private final SifuCollision.Active scene;
        private final EnemyFrame.Entry entry;
        private final PistonCollisionPlan collision;
        private boolean used;
        private Plan(PistonMovePlans owner,PistonContacts.Source source,long ordinal,PistonMovingBlockEntity piston,Entity entity,
                     View view,EnemyFrame.Entry entry,PistonCollisionPlan collision){
            this.owner=owner;scope=view.scope;this.source=source;this.ordinal=ordinal;this.piston=piston;this.entity=entity;
            target=new Target(entity.getUUID(),entry.key());scene=view.scene;this.entry=entry;this.collision=collision;
            tick=owner.level.getGameTime();indexRevision=owner.index.revision();enemyFrame=view.enemies.frame();
        }
        public Scope scope(){return scope;}
        public PistonContacts.Source source(){return source;}
        public long ordinal(){return ordinal;}
        public long enemyFrame(){return enemyFrame;}
        public UUID entity(){return target.uuid;}
        public EnemyFrame.Key target(){return target.key;}
        public PistonCollisionPlan collision(){return collision;}
    }
    private final ServerLevel level;
    private final BlockSectionIndex index;
    private final Supplier<View> current;
    private final LongSupplier clock;
    private final Scope scope;
    private final Map<EnemyFrame.Key,PistonMotionLimit.State> budgets=new HashMap<>();
    private final Map<UUID,Long> ordinals=new HashMap<>();
    private Plan pending;

    public PistonMovePlans(ServerLevel level,Supplier<View> current){this(level,current,System::currentTimeMillis);}
    public PistonMovePlans(ServerLevel level,Supplier<View> current,LongSupplier clock){
        if(!level.getServer().isSameThread()||WorldBlocks.index()==null)throw new IllegalArgumentException("piston planner requires indexed server world");
        this.level=level;index=WorldBlocks.index();this.current=current;this.clock=clock;
        var view=current.get();if(view==null||view.scope==null)throw new IllegalArgumentException("missing piston view");scope=view.scope;
    }
    public static long saveKey(ServerLevel level){
        long key=ShapeProtocol.mapHash(level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());return key==0?1:key;
    }
    private Status context(View v){
        long now=clock.getAsLong();
        if(v==null||!scope.equals(v.scope)||scope.save!=saveKey(level)||level.dimension()!=Level.OVERWORLD||v.enemies==null||v.scene==null||
            v.enemies.session()!=scope.nativeSession||v.enemies.worldGeneration()!=scope.world||!v.scene.matchesScope(scope.nativeSession,scope.world)||
            !v.scene.placement().equals(scope.placement)||v.enemies.receivedMs()>now||now-v.enemies.receivedMs()>200)return Status.CONTEXT;
        return index==WorldBlocks.index()?null:Status.INDEX;
    }
    private Status source(PistonContacts.Source s,PistonMovingBlockEntity piston){
        if(s==null||s.capture()==null||s.action()<=0||s.tick()!=level.getGameTime()||!s.dimension().equals(level.dimension().identifier().toString())||
            piston==null||piston.isRemoved()||level.getBlockEntity(s.pos())!=piston||!s.pos().equals(piston.getBlockPos())||
            !s.movedState().equals(BlockStateKey.encode(piston.getMovedState()))||s.movement()!=piston.getMovementDirection()||
            s.extending()!=piston.isExtending()||s.head()!=piston.isSourcePiston()||s.progress()!=piston.getProgress(1)||
            !Float.isFinite(s.nextProgress())||s.progress()<0||s.nextProgress()<=s.progress()||s.nextProgress()>1)return Status.SOURCE;
        var section=index.snapshot(SectionPos.of(s.pos()).asLong());
        return section==null||section.revision()!=s.sectionRevision()?Status.INDEX:null;
    }
    private EnemyFrame.Entry target(View view,Entity entity){
        if(!(entity instanceof EnemyProxy proxy)||entity.level()!=level||entity.isRemoved())return null;
        var entry=view.enemies.entries().stream().filter(e->e.key().equals(proxy.sifuKey())&&e.alive()).findFirst().orElse(null);
        if(entry==null||entry.id()<0||entry.spawn()==0||!Float.isFinite(entry.location().x())||!Float.isFinite(entry.location().y())||
            !Float.isFinite(entry.location().z())||!Float.isFinite(entry.radius())||!Float.isFinite(entry.halfHeight()))return null;
        var p=scope.placement;var b=entity.getBoundingBox();
        var feet=new Vec3(p.mcX(entry.location().x()),p.mcY(entry.location().z()-entry.halfHeight()),p.mcZ(entry.location().y()));
        return entity.position().distanceToSqr(feet)>.0001||Math.abs(b.getXsize()-entry.radius()*.02)>1e-5||
            Math.abs(b.getZsize()-entry.radius()*.02)>1e-5||Math.abs(b.getYsize()-entry.halfHeight()*.02)>1e-5?null:entry;
    }
    public Attempt propose(PistonContacts.Move move){
        if(!level.getServer().isSameThread())throw new IllegalStateException("piston proposal requires server thread");
        if(move==null||!move.live())return new Attempt(Status.SOURCE,null);
        var source=move.source();var piston=move.piston();var entity=move.entity;var request=move.requested;long ordinal=move.ordinal;
        View view=current.get();Status failure=context(view);
        if(failure==null)failure=source(source,piston);
        if(failure!=null){retire();return new Attempt(failure,null);}
        if(pending!=null)return new Attempt(Status.BUSY,null);
        var entry=target(view,entity);if(entry==null)return new Attempt(Status.TARGET,null);
        if(ordinal<=0||request==null||request.x*source.movement().getStepX()+request.y*source.movement().getStepY()+request.z*source.movement().getStepZ()<=0)
            return new Attempt(Status.REQUEST,null);
        if(ordinal<=ordinals.getOrDefault(source.capture(),0L))return new Attempt(Status.USED,null);
        if(ordinals.size()>=4096&&!ordinals.containsKey(source.capture())||budgets.size()>=4096)return new Attempt(Status.BUSY,null);
        try {
            var collision=PistonCollisionPlan.compute(entity,request,budgets.getOrDefault(entry.key(),PistonMotionLimit.State.empty()),view.scene);
            var after=current.get();
            if(context(after)!=null||after.scene!=view.scene)return new Attempt(Status.CHANGED,null);
            ordinals.put(source.capture(),ordinal);
            pending=new Plan(this,source,ordinal,piston,entity,view,entry,collision);return new Attempt(Status.PLANNED,pending);
        }catch(IllegalArgumentException ex){return new Attempt(Status.REQUEST,null);}
    }
    /** Recheck before dispatch, without treating this local token as a Native lease or shape ACK. */
    public Status validate(Plan plan){
        if(!level.getServer().isSameThread())throw new IllegalStateException("piston validation requires server thread");
        Status status=inspect(plan);
        if(status!=Status.PLANNED&&plan!=null&&pending==plan)retire();
        return status;
    }
    private void retire(){if(pending!=null){pending.used=true;pending=null;}}
    private Status inspect(Plan plan){
        if(plan==null||plan.owner!=this||plan.used||pending!=plan)return Status.USED;
        var view=current.get();Status failure=context(view);if(failure!=null)return failure;
        if(level.getGameTime()!=plan.tick||index.revision()!=plan.indexRevision||view.scene!=plan.scene)return Status.CHANGED;
        failure=source(plan.source,plan.piston);if(failure!=null)return failure;
        var entry=target(view,plan.entity);
        return entry==null||!plan.target.uuid.equals(plan.entity.getUUID())||!entry.equals(plan.entry)||
            view.enemies.frame()<plan.enemyFrame||!plan.entity.getBoundingBox().equals(plan.collision.before())?Status.TARGET:Status.PLANNED;
    }
    public Status take(Plan plan){
        Status status=validate(plan);
        if(plan!=null&&plan.owner==this&&pending==plan){
            plan.used=true;pending=null;
            if(status==Status.PLANNED){budgets.put(plan.target.key,plan.collision.budget());return Status.TAKEN;}
        }
        return status;
    }
    public Status cancel(Plan plan){
        if(!level.getServer().isSameThread())throw new IllegalStateException("piston cancellation requires server thread");
        if(plan==null||plan.owner!=this||pending!=plan||plan.used)return Status.USED;
        plan.used=true;pending=null;return Status.CANCELLED;
    }
}
