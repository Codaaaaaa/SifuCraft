package dev.sifucraft.client;

import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Actual jump/use input, vanilla flight/boost, and landing on the imported floor. Opt-in fixture only. */
final class ElytraCheck {
    private ServerPlayer player;
    private ItemStack oldMain, oldOff, oldChest;
    private Vec3 base;
    private volatile int prepared = -1;
    private volatile boolean attachedRocket;
    private int phase;
    private long at, deadline;
    private double baselineSpeed, boostSpeed, glideStartY;
    private boolean restored;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("elytra check: " + message);
    }
    private void prepare(int step) {
        player.stopFallFlying(); player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        player.setIgnoreFallDamageFromCurrentImpulse(false, Vec3.ZERO);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FIREWORK_ROCKET, 4));
        player.getMainHandItem().set(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.CHEST, step == 0 ? ItemStack.EMPTY : new ItemStack(Items.ELYTRA));
        player.teleportTo(player.level(), base.x, base.y + 24, base.z, Set.of(), 0, 0, true);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.inventoryMenu.broadcastChanges();
        prepared = step;
    }
    void serverTick(MinecraftServer server) {
        if (player == null || phase == 99) return;
        if (!player.level().getEntitiesOfClass(FireworkRocketEntity.class, player.getBoundingBox().inflate(8),
                rocket -> ((dev.sifucraft.client.mixin.FireworkRocketAccessor) rocket).sifucraft$attachedToEntity() == player)
                .isEmpty()) attachedRocket = true;
    }
    void tick(Minecraft mc, InputInjector input, boolean ready) {
        if (phase == 99 || !ready || mc.player == null || mc.gui.screen() != null) return;
        long now = System.currentTimeMillis();
        require(deadline == 0 || now < deadline, "deadline phase=" + phase);
        var server = mc.getSingleplayerServer();
        if (phase == 0) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-elytra-check-"), "disposable fixture world required");
            deadline = now + 30000; var id = mc.player.getUUID();
            server.execute(() -> {
                player = server.getPlayerList().getPlayer(id);
                oldMain = player.getMainHandItem().copy(); oldOff = player.getOffhandItem().copy();
                oldChest = player.getItemBySlot(EquipmentSlot.CHEST).copy(); base = player.position(); prepare(0);
            });
            phase = 1; return;
        }
        if (phase == 1) {
            if (prepared != 0 || mc.player.getY() < base.y + 18 || !mc.player.getMainHandItem().is(Items.FIREWORK_ROCKET)) return;
            mc.player.setYRot(0); mc.player.setXRot(-60);
            at = now; phase = 10; return;
        }
        if (phase == 10) {
            if (now - at < 120) return; // allow the vanilla pick to update to the new view
            require(mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS,
                    "negative-control use ray must miss blocks, including the imported scene");
            input.mouseButton(3, 1, 0); input.mouseButton(3, 0, 0);
            at = now; phase = 2; return;
        }
        if (phase == 2) {
            if (now - at < 400) return;
            require(!mc.player.isFallFlying() && mc.player.getMainHandItem().getCount() == 4,
                    "non-gliding air use does not launch/consume a flight rocket; count=" + mc.player.getMainHandItem().getCount());
            require(!attachedRocket, "negative control has no attached boost rocket");
            server.execute(() -> prepare(1)); phase = 3; return;
        }
        if (phase == 3) {
            if (prepared != 1 || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) ||
                    mc.player.getY() < base.y + 18 || mc.player.onGround()) return;
            mc.player.setYRot(0); mc.player.setXRot(0);
            input.key(44, 1, 0); at = now; phase = 4; return;
        }
        if (phase == 4) {
            if (now - at < 150) return;
            input.key(44, 0, 0); phase = 5; return;
        }
        if (phase == 5) {
            if (!mc.player.isFallFlying()) return;
            require(!mc.player.getAbilities().mayfly && !mc.player.getAbilities().flying,
                    "survival gliding works without creative flight abilities");
            require(mc.player.getBbHeight() < 1, "vanilla gliding dimensions are applied");
            glideStartY = mc.player.getY(); baselineSpeed = 0; at = now; phase = 6; return;
        }
        if (phase == 6) {
            baselineSpeed = Math.max(baselineSpeed, mc.player.getDeltaMovement().horizontalDistance());
            if (now - at < 800) return;
            require(mc.player.isFallFlying() && mc.player.getY() < glideStartY,
                    "unpowered gliding descends rather than hovering");
            input.mouseButton(3, 1, 0); input.mouseButton(3, 0, 0);
            boostSpeed = 0; at = now; phase = 7; return;
        }
        if (phase == 7) {
            boostSpeed = Math.max(boostSpeed, mc.player.getDeltaMovement().horizontalDistance());
            if (now - at < 1400) return;
            require(mc.player.getMainHandItem().getCount() == 3, "one actual boost consumes one rocket");
            require(attachedRocket, "vanilla attached rocket entity was observed on the server");
            require(boostSpeed > baselineSpeed + .2, "rocket accelerates actual flight: " + baselineSpeed + " -> " + boostSpeed);
            require(mc.player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() > 0,
                    "vanilla elytra flight durability is consumed");
            LoggerFactory.getLogger("sifucraft").info("[elytra-test] flight PASS baseline_speed={} boosted_speed={} rockets=3 durability={}",
                    baselineSpeed, boostSpeed, mc.player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue());
            server.execute(() -> {
                player.stopFallFlying(); player.resetFallDistance(); player.setDeltaMovement(Vec3.ZERO);
                player.teleportTo(player.level(), base.x, base.y + 3, base.z, Set.of(), 0, 0, true);
                player.connection.send(new ClientboundSetEntityMotionPacket(player)); prepared = 2;
            });
            at = now; phase = 8; return;
        }
        if (phase == 8) {
            if (prepared != 2 || now - at < 600 || !mc.player.onGround()) return;
            require(Math.abs(mc.player.getY() - base.y) < .15, "landing uses the imported scene floor");
            require(!mc.player.isFallFlying() && mc.player.getBbHeight() > 1.5f, "landing restores normal pose/dimensions");
            phase = 99;
            LoggerFactory.getLogger("sifucraft").info("[elytra-test] PASS: actual jump starts survival gliding, vanilla descent/pose/durability, " +
                    "actual right-click single-consumption attached rocket boost, non-gliding control and imported-floor landing");
        }
    }
    void restore(MinecraftServer server) {
        if (player == null || restored) return;
        restored = true; player.stopFallFlying();
        player.setItemInHand(InteractionHand.MAIN_HAND, oldMain); player.setItemInHand(InteractionHand.OFF_HAND, oldOff);
        player.setItemSlot(EquipmentSlot.CHEST, oldChest);
        LoggerFactory.getLogger("sifucraft").info("[elytra-test] restored fixture equipment");
    }
}
