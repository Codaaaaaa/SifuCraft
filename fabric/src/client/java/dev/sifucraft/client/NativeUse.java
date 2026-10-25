package dev.sifucraft.client;

import dev.sifucraft.bridge.McBridge;
import dev.sifucraft.bridge.NativeInteraction;
import dev.sifucraft.bridge.UseGesture;
import dev.sifucraft.collision.SifuCollision;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Uses vanilla's startUseItem entry, so both the immediate shield and ordinary use paths agree. */
final class NativeUse {
    private final UseGesture gesture = new UseGesture();
    private long session;
    private int world, epoch;
    private Object life;
    private int logged;
    void press() { gesture.press(); }
    void update(McBridge bridge, Minecraft mc) {
        var frame = bridge.latest();
        long next = bridge.peerConnected() ? bridge.peerSession() : 0;
        int nextWorld = frame == null ? 0 : frame.worldGeneration(), nextEpoch = frame == null ? 0 : frame.anchorEpoch();
        if (next != session || world != nextWorld || epoch != nextEpoch || life != mc.player ||
                frame == null || !frame.minecraftOwnsInput() || mc.gui.screen() != null) gesture.invalidateScope();
        session = next; world = nextWorld; epoch = nextEpoch; life = mc.player;
    }
    boolean handle(McBridge bridge, Minecraft mc) {
        if (!SifuCraftClient.minecraftOwnsInput() || mc.gui.screen() != null || !gesture.observed()) return false;
        if (gesture.decided()) return gesture.nativeOwned();
        NativeInteraction candidate = candidate(bridge, mc);
        boolean nativeOwner = candidate != null && !mc.player.isUsingItem() && !mc.options.keyShift.isDown();
        gesture.decide(nativeOwner);
        if (!nativeOwner) return false;
        boolean sent = bridge.sendNativeInteraction(candidate);
        if (logged++ < 100) LoggerFactory.getLogger("sifucraft").info("[interaction] use gesture native={} target={}/{} revision={} sent={}",
                true, candidate.target(), candidate.serial(), candidate.revision(), sent);
        // Full/failed dispatch cannot later become a vanilla item use for the same gesture.
        return true;
    }
    NativeInteraction candidate(McBridge bridge, Minecraft mc) {
        var c = bridge.interaction(); var active = SifuCollision.active();
        if (mc.player == null || !mc.player.isAlive() || mc.level == null || active == null || !bridge.peerConnected() ||
                c == null || !c.available(bridge.peerSession(), bridge.latest(), System.currentTimeMillis())) return null;
        var p = active.placement();
        AABB box = new AABB(p.mcX(c.minimum().x()), p.mcY(c.minimum().z()), p.mcZ(c.minimum().y()),
                p.mcX(c.maximum().x()), p.mcY(c.maximum().z()), p.mcZ(c.maximum().y()));
        Vec3 eye = mc.player.getEyePosition(), end = eye.add(mc.player.getViewVector(1).scale(Math.min(mc.player.blockInteractionRange(), c.rangeCm() / 100.0)));
        var hit = box.clip(eye, end);
        if (hit.isEmpty() && !box.contains(eye)) return null;
        Vec3 point = box.contains(eye) ? eye : hit.get();
        double distance = eye.distanceToSqr(point);
        var vanilla = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (vanilla.getType() != HitResult.Type.MISS && eye.distanceToSqr(vanilla.getLocation()) <= distance + 1e-6) return null;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY &&
                eye.distanceToSqr(mc.hitResult.getLocation()) <= distance + 1e-6) return null;
        var scene = active.clip(eye, point);
        if (scene != null && eye.distanceTo(scene.getLocation()) + .15 < Math.sqrt(distance)) return null;
        return c;
    }
}
