package dev.sifucraft.combat;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/** Unregistered original player integrator. Predictions cannot pick up items or apply world effects. */
public final class VanillaPlayerBody extends Player implements MotionPrediction {
    private EntityDimensions physical=EntityDimensions.scalable(.7f,1.8f);
    private double beforeFall;
    private VanillaFluidBody.Landing landing;
    private int clippedAxes;
    public VanillaPlayerBody(ServerLevel level,float radius,float half,long seed){
        super(level,new GameProfile(new UUID(0x5349465543524146L,seed),"SifuPhysics"));
        physical=EntityDimensions.scalable(radius*2/100,half*2/100);refreshDimensions();setSilent(true);getRandom().setSeed(seed);
    }
    @Override public GameType gameMode(){return GameType.SURVIVAL;}
    @Override public EntityDimensions getDefaultDimensions(Pose pose){return physical==null?EntityDimensions.scalable(.7f,1.8f):physical;}
    public void movement(float sideways,float forward,boolean jump,boolean sprint,boolean sneak){
        xxa=sideways;zza=forward;jumping=jump;setSprinting(sprint);setShiftKeyDown(sneak);
    }
    @Override public boolean hurtServer(ServerLevel level,DamageSource source,float amount){return false;}
    @Override public boolean isIgnoringBlockTriggers(){return true;}
    @Override public boolean isPushable(){return false;}
    @Override public boolean canBeCollidedWith(Entity other){return false;}
    @Override protected void pushEntities(){}
    @Override public boolean canUsePortal(boolean flag){return false;}
    @Override public void gameEvent(Holder<GameEvent> event,Entity source){}
    @Override public void gameEvent(Holder<GameEvent> event){}
    @Override protected void checkFallDamage(double dy,boolean ground,BlockState state,BlockPos pos){
        if(!isInWater())updateFluidInteraction();
        if(!isInWater()&&dy<0)fallDistance-=(float)dy;
        if(ground){if(fallDistance>0)landing=new VanillaFluidBody.Landing(fallDistance,pos.immutable());resetFallDistance();}
    }
    @Override public void move(MoverType type,Vec3 movement){
        var from=position();super.move(type,movement);var actual=position().subtract(from);
        if(Math.abs(movement.x-actual.x)>1e-7)clippedAxes|=1;
        if(Math.abs(movement.y-actual.y)>1e-7)clippedAxes|=2;
        if(Math.abs(movement.z-actual.z)>1e-7)clippedAxes|=4;
    }
    public boolean clipped(int axis){return (clippedAxes&axis)!=0;}
    public void beginPrediction(){beforeFall=fallDistance;landing=null;clippedAxes=0;}
    public void reconcileFall(double actualY,boolean ground){
        fallDistance=beforeFall;landing=null;var pos=BlockPos.containing(getX(),getY()-.01,getZ());checkFallDamage(actualY,ground,level().getBlockState(pos),pos);
    }
    public VanillaFluidBody.Landing consumeLanding(){var result=landing;landing=null;return result;}
}
