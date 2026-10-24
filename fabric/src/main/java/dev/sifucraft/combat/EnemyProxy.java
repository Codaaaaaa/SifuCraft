package dev.sifucraft.combat;

import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.collision.MapPlacement;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** An invisible target whose position, dimensions and life belong to Sifu. No independent AI. */
public final class EnemyProxy extends Mob {
    private static final EntityDataAccessor<Integer> SIFU_ID = SynchedEntityData.defineId(EnemyProxy.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SPAWN = SynchedEntityData.defineId(EnemyProxy.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(EnemyProxy.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(EnemyProxy.class, EntityDataSerializers.FLOAT);

    public EnemyProxy(EntityType<? extends EnemyProxy> type, Level level) {
        super(type, level);
        setNoAi(true);
        setNoGravity(true);
        setSilent(true);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SIFU_ID, -1);
        builder.define(SPAWN, 0);
        builder.define(WIDTH, 0.7f);
        builder.define(HEIGHT, 1.84f);
    }

    public EnemyFrame.Key sifuKey() { return new EnemyFrame.Key(entityData.get(SIFU_ID), entityData.get(SPAWN)); }

    public void mirror(EnemyFrame.Entry enemy, MapPlacement placement) {
        entityData.set(SIFU_ID, enemy.id());
        entityData.set(SPAWN, enemy.spawn());
        entityData.set(WIDTH, enemy.radius() * 2 / 100.0f);
        entityData.set(HEIGHT, enemy.halfHeight() * 2 / 100.0f);
        double x = placement.mcX(enemy.location().x());
        double y = placement.mcY(enemy.location().z() - enemy.halfHeight());
        double z = placement.mcZ(enemy.location().y());
        setPos(x, y, z);
        float yaw = enemy.yaw() - 90.0f;
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
        setDeltaMovement(Vec3.ZERO);
        var max = getAttribute(Attributes.MAX_HEALTH);
        double wantedMax = Math.clamp(enemy.maxHealth() / Protocol.SIFU_HP_PER_MC_HP, 1.0, 1024.0);
        if (max != null && max.getBaseValue() != wantedMax) max.setBaseValue(wantedMax);
        setHealth(Math.max(1.0f, enemy.health() / Protocol.SIFU_HP_PER_MC_HP));
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return EntityDimensions.scalable(entityData.get(WIDTH), entityData.get(HEIGHT));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (accessor.equals(WIDTH) || accessor.equals(HEIGHT)) refreshDimensions();
    }

    @Override
    public void tick() {
        setDeltaMovement(Vec3.ZERO);
        super.tick();
        setDeltaMovement(Vec3.ZERO);
    }

    @Override public boolean isPickable() { return !isRemoved() && isAlive(); }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith(Entity other) { return false; }

    // S1 deliberately has no damage path. S2 will route Minecraft's resolved damage to Sifu.
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
}
