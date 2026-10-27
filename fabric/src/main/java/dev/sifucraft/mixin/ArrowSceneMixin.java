package dev.sifucraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sifucraft.bridge.ShapeProtocol;
import dev.sifucraft.collision.SifuCollision;
import dev.sifucraft.combat.SceneProjectiles;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Original wall stops an arrow; an opened/removed wall releases it by vanilla startFalling. */
@Mixin(AbstractArrow.class)
public abstract class ArrowSceneMixin {
    @Unique private Vec3 sifucraft$face;
    @Unique private long sifucraft$map;
    @Shadow private void startFalling(){throw new AssertionError();}
    @Shadow protected abstract boolean isInGround();
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult sifucraft$nearestFace(Level level,ClipContext context,Operation<BlockHitResult> original){
        return (BlockHitResult)SceneProjectiles.clip((AbstractArrow)(Object)this,original.call(level,context));
    }
    @Inject(method="onHitBlock",at=@At("TAIL"))
    private void sifucraft$anchor(BlockHitResult hit,CallbackInfo ci){
        var arrow=(AbstractArrow)(Object)this;var scene=SifuCollision.active();sifucraft$face=null;
        if(scene==null||arrow.level().dimension()!=Level.OVERWORLD||!arrow.level().getBlockState(hit.getBlockPos()).getCollisionShape(arrow.level(),hit.getBlockPos()).isEmpty())return;
        var face=hit.getDirection();var inside=hit.getLocation().add(-face.getStepX()*1e-4,-face.getStepY()*1e-4,-face.getStepZ()*1e-4);
        if(scene.solidAt(inside.x,inside.y,inside.z)){sifucraft$face=inside;sifucraft$map=ShapeProtocol.mapHash(scene.placement().map());}
    }
    @Inject(method="tick",at=@At("HEAD"))
    private void sifucraft$releaseRemovedWall(CallbackInfo ci){
        var arrow=(AbstractArrow)(Object)this;var scene=SifuCollision.active();var point=sifucraft$face;
        if(point!=null&&isInGround()&&scene!=null&&ShapeProtocol.mapHash(scene.placement().map())==sifucraft$map&&!scene.solidAt(point.x,point.y,point.z)){
            sifucraft$face=null;startFalling();
        }
    }
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void sifucraft$saveFace(ValueOutput output,CallbackInfo ci){
        var p=sifucraft$face;if(p==null)return;var out=output.child("sifucraft_native_face");out.putLong("map",sifucraft$map);out.putDouble("x",p.x);out.putDouble("y",p.y);out.putDouble("z",p.z);
    }
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void sifucraft$loadFace(ValueInput input,CallbackInfo ci){
        var in=input.childOrEmpty("sifucraft_native_face");sifucraft$map=in.getLongOr("map",0);double x=in.getDoubleOr("x",Double.NaN),y=in.getDoubleOr("y",Double.NaN),z=in.getDoubleOr("z",Double.NaN);
        sifucraft$face=sifucraft$map!=0&&Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)?new Vec3(x,y,z):null;
    }
}
