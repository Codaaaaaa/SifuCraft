package dev.sifucraft.blocks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Signal-only presence of the Native-controlled player, never a movement or damage owner. */
public final class PlayerSignalBody extends Mob {
    private float width=.6f,height=1.8f;
    public PlayerSignalBody(EntityType<? extends PlayerSignalBody> type,Level level) {
        super(type,level);
        setNoAi(true);setNoGravity(true);setSilent(true);setInvisible(true);noPhysics=true;
    }
    void place(double x,double feetY,double z,float width,float height) {
        this.width=width;this.height=height;refreshDimensions();setPos(x,feetY,z);setDeltaMovement(Vec3.ZERO);
    }
    @Override protected EntityDimensions getDefaultDimensions(Pose pose){return EntityDimensions.scalable(width>0?width:.6f,height>0?height:1.8f);}
    @Override public void tick(){setDeltaMovement(Vec3.ZERO);}
    @Override public void checkDespawn(){}
    @Override public boolean isPickable(){return false;}
    @Override public boolean isPushable(){return false;}
    @Override public boolean canBeCollidedWith(Entity other){return false;}
    @Override public void push(double x,double y,double z){}
    @Override public void push(Vec3 velocity){}
    @Override public boolean hurtServer(ServerLevel level,DamageSource source,float amount){return false;}
}
