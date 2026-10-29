package dev.sifucraft.client.mixin;

import dev.sifucraft.bridge.WorldView;
import dev.sifucraft.client.SifuCraftClient;
import dev.sifucraft.client.WorldEffects;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Update before Camera builds its frustum/projection, so culling and capture use the same view. */
@Mixin(Camera.class)
public abstract class WorldCameraMixin {
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Unique private WorldView sifucraft$nativeView;

    @Inject(method = "update", at = @At("HEAD"))
    private void sifucraft$viewForFrame(CallbackInfo ci) { sifucraft$nativeView = SifuCraftClient.nativeWorldView(); }

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
    private void sifucraft$nativePose(CallbackInfo ci) {
        var view = sifucraft$nativeView;
        if (view == null) return;
        setPosition(view.x(), view.y(), view.z());
        setRotation(view.yaw(), view.pitch());
        // The handedness conversion reverses local camera roll.
        rotation.rotateZ((float) Math.toRadians(-view.roll()));
        rotation.transform(forwards.set(0, 0, -1));
        rotation.transform(up.set(0, 1, 0));
        rotation.transform(left.set(-1, 0, 0));
    }
    @Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
    private void sifucraft$nativeFov(CallbackInfoReturnable<Float> cir) {
        if (sifucraft$nativeView != null) cir.setReturnValue(sifucraft$nativeView.verticalFov());
    }

    @Redirect(method = "getMaxZoom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private net.minecraft.world.phys.BlockHitResult sifucraft$cameraWall(net.minecraft.world.level.Level level,
            net.minecraft.world.level.ClipContext ray) {
        var vanilla = level.clip(ray);
        if (!WorldEffects.active() || !SifuCraftClient.minecraftOwnsInput()) return vanilla;
        return dev.sifucraft.client.CameraCollision.nearest(dev.sifucraft.collision.SifuCollision.active(),
                ray.getFrom(), ray.getTo(), vanilla);
    }
}
