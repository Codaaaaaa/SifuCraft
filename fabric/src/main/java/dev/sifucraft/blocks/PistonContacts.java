package dev.sifucraft.blocks;

import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.combat.EnemyProxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Call-stack-scoped observation of vanilla piston contacts. No Native commands or movement changes. */
public final class PistonContacts {
    private PistonContacts(){}
    public enum Phase { COLLIDED, CARRIED }
    public record Source(UUID capture,long action,String dimension,BlockPos pos,String movedState,
                         long sectionRevision,long tick,Direction movement,boolean extending,boolean head,
                         float progress,float nextProgress,Phase phase){}
    public record Contact(Source source,UUID entity,EnemyFrame.Key enemy,AABB before,Vec3 requested,
                          Vec3 actual,Vec3 velocity){}
    private record Active(Capture capture,Source source,PistonMovingBlockEntity piston){}
    private static final ThreadLocal<Capture> CAPTURE=new ThreadLocal<>();
    private static final ThreadLocal<Active> ACTIVE=new ThreadLocal<>();
    public static boolean observing(){return CAPTURE.get()!=null;}
    /** Only the actual redirected vanilla move call can create this proposal capability. */
    public static final class Move {
        private final Active active;
        final Entity entity;
        final Vec3 requested;
        final long ordinal;
        private Move(Active active,Entity entity,Vec3 requested){
            this.active=active;this.entity=entity;this.requested=requested;ordinal=active.capture.contacts.size()+1L;
        }
        boolean live(){return ACTIVE.get()==active&&CAPTURE.get()==active.capture;}
        Source source(){return active.source;}
        PistonMovingBlockEntity piston(){return active.piston;}
    }

    /** Diagnostics only: this scope has no bridge/save identity and is not a wire credential. */
    public static final class Capture {
        private final UUID id=UUID.randomUUID();
        private final ServerLevel level;
        private final IdentityHashMap<PistonMovingBlockEntity,Long> actions=new IdentityHashMap<>();
        private final List<Contact> contacts=new ArrayList<>();
        private final PistonMovePlans planner;
        private final Consumer<PistonMovePlans.Attempt> onPlan;
        private final List<PistonMovePlans.Attempt> plans=new ArrayList<>();
        public Capture(ServerLevel level){this(level,null,null);}
        public Capture(ServerLevel level,PistonMovePlans planner){this(level,planner,null);}
        public Capture(ServerLevel level,PistonMovePlans planner,Consumer<PistonMovePlans.Attempt> onPlan){this.level=level;this.planner=planner;this.onPlan=onPlan;}
        public List<Contact> contacts(){return List.copyOf(contacts);}
        public List<PistonMovePlans.Attempt> plans(){return List.copyOf(plans);}
        public void run(Runnable action){
            if(!level.getServer().isSameThread())throw new IllegalStateException("piston capture requires server thread");
            var previous=CAPTURE.get();var active=ACTIVE.get();CAPTURE.set(this);ACTIVE.remove();
            try{action.run();}finally{if(previous==null)CAPTURE.remove();else CAPTURE.set(previous);if(active==null)ACTIVE.remove();else ACTIVE.set(active);}
        }
    }

    public static void source(Level level,BlockPos pos,float next,PistonMovingBlockEntity piston,Phase phase,Runnable action){
        var capture=CAPTURE.get();
        if(capture==null||level!=capture.level||level.getBlockEntity(pos)!=piston||piston.isRemoved()||!pos.equals(piston.getBlockPos())){action.run();return;}
        if(capture.actions.size()>=4096&&!capture.actions.containsKey(piston))throw new IllegalStateException("piston action observation capacity");
        long id=capture.actions.computeIfAbsent(piston,ignored->(long)capture.actions.size()+1);
        var index=WorldBlocks.index();var section=index==null?null:index.snapshot(SectionPos.of(pos).asLong());
        var source=new Source(capture.id,id,level.dimension().identifier().toString(),pos.immutable(),BlockStateKey.encode(piston.getMovedState()),
            section==null?0:section.revision(),level.getGameTime(),piston.getMovementDirection(),piston.isExtending(),piston.isSourcePiston(),piston.getProgress(1),next,phase);
        var previous=ACTIVE.get();ACTIVE.set(new Active(capture,source,piston));
        try{action.run();}finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    }

    public static void move(Entity entity,MoverType type,Vec3 movement){
        var active=ACTIVE.get();
        if(active==null||type!=MoverType.PISTON||entity.level()!=active.capture.level){entity.move(type,movement);return;}
        if(active.capture.contacts.size()>=4096)throw new IllegalStateException("piston contact observation capacity");
        var position=entity.position();var box=entity.getBoundingBox();
        if(active.capture.planner!=null){
            var attempt=active.capture.planner.propose(new Move(active,entity,movement));active.capture.plans.add(attempt);
            if(active.capture.onPlan!=null)active.capture.onPlan.accept(attempt);
        }
        entity.move(type,movement); // preserve vanilla, including its per-tick cap and noPhysics branch
        var key=entity instanceof EnemyProxy proxy?proxy.sifuKey():null;
        active.capture.contacts.add(new Contact(active.source,entity.getUUID(),key,box,movement,entity.position().subtract(position),entity.getDeltaMovement()));
    }
}
