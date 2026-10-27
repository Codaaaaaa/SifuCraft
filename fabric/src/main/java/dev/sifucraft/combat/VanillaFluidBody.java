package dev.sifucraft.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/** Unregistered vanilla land-mob integrator. Actual effects belong to the hit mirror. */
public final class VanillaFluidBody extends Cow implements MotionPrediction {
    public record Landing(double distance,BlockPos pos){}
    private double beforeFall;private Landing landing;
    private int clippedAxes;
    private EntityDimensions physical=EntityDimensions.scalable(.7f,1.8f);
    public VanillaFluidBody(ServerLevel level,float radius,float half,long seed,boolean swim){
        super(EntityTypes.COW,level);physical=EntityDimensions.scalable(radius*2/100,half*2/100);refreshDimensions();
        setSilent(true);getRandom().setSeed(seed);
        if(swim)goalSelector.addGoal(0,new FloatGoal(this));
    }
    @Override protected void registerGoals(){} // no wandering/pathfinding/fighting authority
    @Override public EntityDimensions getDefaultDimensions(Pose pose){return physical==null?EntityDimensions.scalable(.7f,1.8f):physical;}
    @Override public boolean hurtServer(ServerLevel level,DamageSource source,float amount){return false;}
    @Override public boolean isIgnoringBlockTriggers(){return true;}
    @Override public boolean isPushable(){return false;}
    @Override public boolean canBeCollidedWith(Entity other){return false;}
    @Override protected void pushEntities(){}
    @Override public boolean canUsePortal(boolean flag){return false;}
    @Override public void gameEvent(Holder<GameEvent> event,Entity source){}
    @Override public void gameEvent(Holder<GameEvent> event){}
    @Override protected void checkFallDamage(double dy,boolean ground,BlockState state,BlockPos pos){
        // LivingEntity refreshes fluid contact here when a move re-enters water.
        // Preserve that timing (including vanilla splash RNG) before deferring
        // the landing callback; waiting for baseTick changes the next FloatGoal.
        if(!isInWater())updateFluidInteraction();
        // Prediction cannot stomp farmland, play a landing event or cause damage.
        // Keep vanilla's accumulated fall state; actual landing is handled only
        // after the physical receipt, never at the speculative endpoint.
        // Installed Entity.checkFallDamage accumulates float(dy), including the
        // final ground move, and excludes water. Only defer its world callback.
        if(!isInWater()&&dy<0)fallDistance-=(float)dy;
        if(ground){if(fallDistance>0)landing=new Landing(fallDistance,pos.immutable());resetFallDistance();}
    }
    @Override public void move(MoverType type,net.minecraft.world.phys.Vec3 movement){
        var from=position();super.move(type,movement);var actual=position().subtract(from);
        if(Math.abs(movement.x-actual.x)>1e-7)clippedAxes|=1;
        if(Math.abs(movement.y-actual.y)>1e-7)clippedAxes|=2;
        if(Math.abs(movement.z-actual.z)>1e-7)clippedAxes|=4;
    }
    /** Vanilla has already resolved these contacts before later fluid forces. */
    public boolean clipped(int axis){return (clippedAxes&axis)!=0;}
    public void beginPrediction(){beforeFall=fallDistance;landing=null;clippedAxes=0;}
    public void reconcileFall(double actualY,boolean ground){
        fallDistance=beforeFall;landing=null;var pos=BlockPos.containing(getX(),getY()-.01,getZ());checkFallDamage(actualY,ground,level().getBlockState(pos),pos);
    }
    public Landing consumeLanding(){var result=landing;landing=null;return result;}
}
