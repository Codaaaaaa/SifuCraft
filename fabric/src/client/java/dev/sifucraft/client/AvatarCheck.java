package dev.sifucraft.client;

import dev.sifucraft.client.mixin.KeyMappingAccessor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Set;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Opt-in vanilla F5, pose/equipment and paired-depth checks in a disposable save. */
final class AvatarCheck {
    private static final int HOLD_MS = Math.clamp(Integer.getInteger("sifucraft.avatarHoldMs", 650), 650, 3000);
    private static final String[] CASES = {"first", "back", "front", "crouch", "equipment", "swing", "walk", "first-return"};
    private static AvatarCheck current;
    private ServerPlayer player;
    private List<ItemStack> inventory;
    private Vec3 base;
    private CameraType oldCamera;
    private boolean oldHud, started, done, captured, observed;
    private volatile boolean prepared, restored;
    private int sample = -1;
    private long deadline, armAt, keyAt, swingAt;
    private InputInjector input;

    AvatarCheck() {
        require(DevWorld.LEVEL_ID.startsWith("sifucraft-avatar-check-"), "isolated save required");
        current = this;
    }
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError("avatar check: " + why); }
    private void key(KeyMapping binding, boolean down) {
        var key = ((KeyMappingAccessor)binding).sifucraft$key();
        require(key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD, "keyboard fixture binding");
        input.key(key.getValue(), down ? InputInjector.PRESS : InputInjector.RELEASE, 0);
    }
    private void prepare() {
        player.getInventory().clearContent();
        if (sample >= 4 && sample <= 6) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        }
        player.inventoryMenu.broadcastChanges(); prepared = true;
    }
    void tick(Minecraft mc, InputInjector injector, boolean ready) {
        if (done || !ready || mc.player == null || mc.gui.screen() != null) return;
        long now = System.currentTimeMillis(); input = injector;
        if (!started) {
            require(SifuCraftClient.worldLayersActive(), "world layer required");
            started = true; deadline = now + 60000; oldCamera = mc.options.getCameraType(); oldHud = mc.gui.hud.isHidden();
            if (!oldHud) mc.gui.hud.toggle();
            var id = mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> {
                player = mc.getSingleplayerServer().getPlayerList().getPlayer(id); base = player.position();
                inventory = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                        .mapToObj(i -> player.getInventory().getItem(i).copy()).toList();
                prepared = true;
            }); return;
        }
        require(now < deadline, "deadline sample=" + sample);
        mc.particleEngine.clearParticles();
        if (captured || sample == -1 && prepared) {
            input.releaseAll(); captured = observed = prepared = false; armAt = 0; ++sample;
            if (sample == CASES.length) {
                mc.getSingleplayerServer().execute(this::restoreServer); return;
            }
            mc.getSingleplayerServer().execute(this::prepare);
        }
        if (sample == CASES.length) {
            if (restored) {
                restoreClient(mc); done = true;
                LoggerFactory.getLogger("sifucraft").info("[avatar-test] PASS: vanilla F5 first/back/front, Steve, crouch/equipment/swing/walk, paired visible depth, first-person body removal; fixture restored");
            }
            return;
        }
        if (!prepared || sample < 0) return;
        CameraType wanted = sample == 0 || sample == 7 ? CameraType.FIRST_PERSON :
                sample == 2 ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK;
        if (mc.options.getCameraType() != wanted) {
            if (now >= keyAt) { key(mc.options.keyTogglePerspective, true); key(mc.options.keyTogglePerspective, false); keyAt = now + 200; }
            return;
        }
        boolean equipped = sample >= 4 && sample <= 6;
        if (mc.player.getMainHandItem().is(Items.IRON_SWORD) != equipped) return;
        if (armAt == 0) {
            mc.player.setYRot(0); mc.player.yRotO = 0; mc.player.setXRot(0); mc.player.xRotO = 0;
            if (sample == 3) key(mc.options.keyShift, true);
            if (sample == 6) key(mc.options.keyUp, true);
            armAt = now + HOLD_MS;
            LoggerFactory.getLogger("sifucraft").info("[avatar-test] show case={} camera={}", CASES[sample], wanted);
        }
        if (sample == 5 && now >= swingAt) {
            mc.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            swingAt = now + 300;
        }
    }
    static void observe(LevelRenderState state) {
        var c = current;
        if (c == null || c.done || c.armAt == 0 || c.sample < 0 || c.sample >= CASES.length) return;
        var avatars = state.entityRenderStates.stream().filter(AvatarRenderState.class::isInstance).map(AvatarRenderState.class::cast).toList();
        if (c.sample == 0 || c.sample == 7) { c.observed = avatars.isEmpty(); return; }
        if (avatars.size() != 1) return;
        var avatar = avatars.getFirst();
        require(avatar.skin.body().texturePath().toString().contains("/wide/steve"), "wide Steve skin");
        boolean pose = switch (c.sample) {
            case 3 -> avatar.isCrouching;
            case 4 -> avatar.chestEquipment.is(Items.DIAMOND_CHESTPLATE) &&
                    avatar.getMainHandItemStack().is(Items.IRON_SWORD) &&
                    (avatar.leftHandItemStack.is(Items.SHIELD) || avatar.rightHandItemStack.is(Items.SHIELD));
            case 5 -> avatar.swingAnimation > .01;
            case 6 -> avatar.walkAnimationSpeed > .01;
            default -> true;
        };
        c.observed |= pose;
    }
    static void inspect(MemorySegment color, MemorySegment depth, int width, int height) {
        var c = current;
        if (c == null || c.done || c.captured || !c.observed || c.armAt == 0 || System.currentTimeMillis() < c.armAt) return;
        int visible = 0, covered = 0;
        for (long at = 0; at < (long)width*height*4; at += 4) if (Byte.toUnsignedInt(color.get(ValueLayout.JAVA_BYTE, at+3)) > 8) {
            visible++; float z = depth.get(ValueLayout.JAVA_FLOAT, at);
            if (Float.isFinite(z) && z > 0 && z <= 1) covered++;
        }
        boolean first = c.sample == 0 || c.sample == 7;
        require(first ? visible == 0 : visible > 32 && covered >= visible * .95, CASES[c.sample]+" paired pixels="+visible+" depth="+covered);
        if (!first) WorldEffectsCheck.evidence(color, width, height, "avatar-" + CASES[c.sample]);
        LoggerFactory.getLogger("sifucraft").info("[avatar-test] case={} visible={} depth={} pose=true", CASES[c.sample], visible, covered);
        c.captured = true;
    }
    private void restoreServer() {
        if (restored || player == null || inventory == null) return;
        for (int i = 0; i < inventory.size(); i++) player.getInventory().setItem(i, inventory.get(i));
        player.teleportTo(player.level(), base.x, base.y, base.z, Set.of(), 0, 0, true);
        player.setDeltaMovement(Vec3.ZERO); player.inventoryMenu.broadcastChanges(); restored = true;
    }
    void restore(MinecraftServer server) { restoreServer(); }
    void restoreClient(Minecraft mc) {
        if (!started) return;
        if (input != null) input.releaseAll();
        mc.options.setCameraType(oldCamera);
        if (mc.gui.hud.isHidden() != oldHud) mc.gui.hud.toggle();
    }
}
