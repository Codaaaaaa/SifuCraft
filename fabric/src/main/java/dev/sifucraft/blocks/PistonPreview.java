package dev.sifucraft.blocks;

import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.WorldMotionProtocol.Reply;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.combat.EnemyProxy;
import dev.sifucraft.mixin.PistonAccess;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Calls the installed vanilla contact routines against shadow bodies. No entity/world writes. */
public final class PistonPreview {
    private PistonPreview(){}
    public record Move(PistonContacts.Source source,UUID entity,EnemyFrame.Key enemy,AABB before,Vec3 requested,
                       Vec3 limited,Vec3 collided,Vec3 velocity,PistonMotionLimit.State budget){}
    public record Body(UUID entity,EnemyFrame.Key enemy,Vec3 before,Vec3 after,Vec3 velocity,AABB box,PistonMotionLimit.State budget){}
    public record Result(List<Move> moves,List<Body> bodies){public Result{moves=List.copyOf(moves);bodies=List.copyOf(bodies);}}
    private static final ThreadLocal<Session> CURRENT=new ThreadLocal<>();
    private static final class Shadow {
        final Vec3 initial;Vec3 position,velocity;AABB box;PistonMotionLimit.State budget;
        Shadow(Entity e,Map<EnemyFrame.Key,PistonMotionLimit.State> budgets,Map<EnemyFrame.Key,Reply> bindings){
            position=e.position();velocity=e.getDeltaMovement();box=e.getBoundingBox();
            budget=e instanceof EnemyProxy proxy?budgets.getOrDefault(proxy.sifuKey(),PistonMotionLimit.State.empty()):PistonMotionLimit.State.empty();
            var binding=e instanceof EnemyProxy proxy?bindings.get(proxy.sifuKey()):null;
            if(binding!=null){
                var offset=binding.rootOffset();
                position=position.add(offset.x()/100.0,offset.z()/100.0+e.getBbHeight()/2-binding.rootHalfHeight()/100.0,offset.y()/100.0);
                double radius=binding.rootRadius()/100.0,half=binding.rootHalfHeight()/100.0;
                box=new AABB(position.x-radius,position.y,position.z-radius,position.x+radius,position.y+2*half,position.z+radius);
            }
            initial=position;
        }
    }
    private static final class Session {
        final ServerLevel level;final Map<EnemyFrame.Key,PistonMotionLimit.State> budgets;
        final Map<EnemyFrame.Key,Reply> bindings;
        final IdentityHashMap<Entity,Shadow> bodies=new IdentityHashMap<>();final List<Move> moves=new ArrayList<>();
        Session(ServerLevel level,Map<EnemyFrame.Key,PistonMotionLimit.State> budgets,Map<EnemyFrame.Key,Reply> bindings){this.level=level;this.budgets=budgets;this.bindings=bindings;}
        Shadow body(Entity e){if(bodies.size()>=4096&&!bodies.containsKey(e))throw new IllegalStateException("piston preview capacity");return bodies.computeIfAbsent(e,x->new Shadow(x,budgets,bindings));}
        void move(PistonContacts.Move capability){
            if(!capability.live()||capability.entity.level()!=level)throw new IllegalStateException("piston preview requires live vanilla source");
            if(moves.size()>=4096)throw new IllegalStateException("piston preview contact capacity");
            var e=capability.entity;var b=body(e);var requested=capability.requested;var before=b.box;
            Vec3 limited=requested,collided=requested;
            if(!e.noPhysics||e instanceof EnemyProxy){
                var limit=PistonMotionLimit.plan(b.budget,level.getGameTime(),requested);b.budget=limit.state();limited=limit.limited();
                collided=Entity.collideBoundingBox(e,limited,b.box,level,level.getEntityCollisions(e,b.box.expandTowards(limited)));
            }
            b.position=b.position.add(collided);b.box=b.box.move(collided);
            moves.add(new Move(capability.source(),e.getUUID(),e instanceof EnemyProxy p?p.sifuKey():null,before,requested,limited,collided,b.velocity,b.budget));
        }
        Result result(){return new Result(moves,bodies.entrySet().stream().map(e->{var b=e.getValue();return new Body(e.getKey().getUUID(),
            e.getKey() instanceof EnemyProxy p?p.sifuKey():null,b.initial,b.position,b.velocity,b.box,b.budget);}).toList());}
    }
    public static Result run(ServerLevel level,BlockPos pos,PistonMovingBlockEntity piston,float next,SifuCollision.Active scene,
                             Map<EnemyFrame.Key,PistonMotionLimit.State> budgets){
        return run(level,pos,piston,next,scene,budgets,UUID.randomUUID());
    }
    static Result run(ServerLevel level,BlockPos pos,PistonMovingBlockEntity piston,float next,SifuCollision.Active scene,
                      Map<EnemyFrame.Key,PistonMotionLimit.State> budgets,UUID source){
        return run(level,pos,piston,next,scene,budgets,source,Map.of());
    }
    static Result run(ServerLevel level,BlockPos pos,PistonMovingBlockEntity piston,float next,SifuCollision.Active scene,
                      Map<EnemyFrame.Key,PistonMotionLimit.State> budgets,UUID source,Map<EnemyFrame.Key,Reply> bindings){
        if(!level.getServer().isSameThread()||level.getBlockEntity(pos)!=piston||piston.isRemoved()||
            !Float.isFinite(next)||next<=piston.getProgress(1)||next>1)throw new IllegalArgumentException("registered advancing piston required");
        var session=new Session(level,Map.copyOf(budgets),Map.copyOf(bindings));var capture=PistonContacts.Capture.preview(level,source,session::move);var previous=CURRENT.get();
        CURRENT.set(session);
        try{
            Runnable action=()->capture.run(()->{PistonAccess.sifucraft$collide(level,pos,next,piston);PistonAccess.sifucraft$carry(level,pos,next,piston);});
            if(scene==null)action.run();else SceneBlockSupport.withScene(scene,()->SifuCollision.withQueryView(scene,()->{action.run();return null;}));
            return session.result();
        }finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static boolean active(){return CURRENT.get()!=null;}
    public static AABB box(Entity entity){var s=CURRENT.get();return s==null?entity.getBoundingBox():s.body(entity).box;}
    public static Vec3 position(Entity entity){var s=CURRENT.get();return s==null?entity.position():s.body(entity).position;}
    public static Vec3 velocity(Entity entity){var s=CURRENT.get();return s==null?entity.getDeltaMovement():s.body(entity).velocity;}
    public static void velocity(Entity entity,double x,double y,double z){var s=CURRENT.get();if(s==null){if(!PistonMachines.suppress(entity))entity.setDeltaMovement(x,y,z);}else s.body(entity).velocity=new Vec3(x,y,z);}
    public static List<Entity> entities(Level level,Entity excluded,AABB area,Predicate<Entity> predicate){
        var s=CURRENT.get();
        if(s==null)return predicate==null?level.getEntities(excluded,area):level.getEntities(excluded,area,predicate);
        // Preview movements are bounded to one piston cell. Include bodies which moved
        // into the second query and filter using their shadow box and vanilla predicate.
        var nearby=predicate==null?level.getEntities(excluded,area.inflate(1.1)):level.getEntities(excluded,area.inflate(1.1),e->true);
        return nearby.stream().filter(e->box(e).intersects(area)&&(predicate==null||predicate.test(e))).toList();
    }
    public static boolean grounded(Entity entity){
        var s=CURRENT.get();if(s==null||!(entity instanceof EnemyProxy)||entity.onGround())return entity.onGround();
        var b=s.body(entity).box;
        var feet=new AABB(b.minX+1e-7,b.minY-.02,b.minZ+1e-7,b.maxX-1e-7,b.minY+.001,b.maxZ-1e-7);
        for(var shape:s.level.getBlockCollisions(entity,feet))if(!shape.isEmpty())return true;
        return false;
    }
}
