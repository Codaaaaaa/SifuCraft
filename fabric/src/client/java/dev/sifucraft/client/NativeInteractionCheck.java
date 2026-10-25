package dev.sifucraft.client;

import dev.sifucraft.collision.SifuCollision;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Actual input against a fake native target, including collision replacement and vanilla priority. */
final class NativeInteractionCheck {
    private volatile int prepared = -1;
    private ServerPlayer player;
    private ItemStack oldMain, oldOff;
    private Vec3 base;
    private BlockPos blocker;
    private BlockState oldBlock;
    private int phase;
    private long at, deadline;
    private boolean restored;
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError("interaction check: " + why); }
    private boolean doorClosed() {
        var active = SifuCollision.active();
        return active != null && active.solidAt(-143.4375, 65, 62.625);
    }
    private void checkDoorNormal(boolean closed) {
        var active = SifuCollision.active();
        var normal = active.wallNormal(new AABB(-143.9, 64, 62.3, -143.6, 65.8, 62.8), 64.25, .3);
        require(closed ? normal != null && normal.x > .9 : normal == null,
                "dynamic door contributes wall normal only while closed");
    }
    private void prepare(int n) {
        player.stopUsingItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, n == 0 || n == 3 ? new ItemStack(Items.STONE, 8) : ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        player.level().setBlockAndUpdate(blocker, n == 2 ? Blocks.STONE.defaultBlockState() : oldBlock);
        player.teleportTo(player.level(), base.x, base.y, base.z, Set.of(), -90, 0, true);
        player.setDeltaMovement(Vec3.ZERO); player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.inventoryMenu.broadcastChanges(); prepared = n;
    }
    void tick(Minecraft mc, InputInjector input, boolean ready) {
        if (phase == 99 || !ready || mc.player == null || mc.gui.screen() != null) return;
        long now = System.currentTimeMillis();
        require(deadline == 0 || now < deadline, "deadline phase=" + phase);
        var server = mc.getSingleplayerServer();
        if (phase == 0) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-interaction-check-"), "isolated world required");
            deadline = now + 25000; var id = mc.player.getUUID();
            server.execute(() -> {
                player = server.getPlayerList().getPlayer(id); base = player.position();
                oldMain = player.getMainHandItem().copy(); oldOff = player.getOffhandItem().copy();
                blocker = new BlockPos(-144, 65, 62); oldBlock = player.level().getBlockState(blocker); prepare(0);
            }); phase = 1; return;
        }
        if (phase == 1) {
            if (prepared != 0 || !doorClosed() || mc.player.getMainHandItem().getCount() != 8) return;
            checkDoorNormal(true);
            mc.player.setYRot(-90); mc.player.setXRot(0); at = now; phase = 2; return;
        }
        if (phase == 2) {
            if (now - at < 150) return;
            input.mouseButton(3, 1, 0); at = now; phase = 3; return;
        }
        if (phase == 3) {
            if (now - at < 450) return;
            input.mouseButton(3, 0, 0);
            require(mc.player.getMainHandItem().getCount() == 8 && !mc.player.isUsingItem(), "native gesture neither places stone nor raises shield");
            require(!doorClosed(), "native result replaced closed-door collision with empty open layer");
            checkDoorNormal(false);
            server.execute(() -> prepare(1)); phase = 4; at = now; return;
        }
        if (phase == 4) {
            if (prepared != 1 || now - at < 250 || !mc.player.getMainHandItem().isEmpty()) return;
            input.key(225, 1, 0); input.mouseButton(3, 1, 0); at = now; phase = 5; return;
        }
        if (phase == 5) {
            if (now - at < 250) return;
            require(mc.player.isUsingItem() && mc.player.getUseItem().is(Items.SHIELD), "Shift leaves vanilla shield use active");
            input.mouseButton(3, 0, 0); input.key(225, 0, 0);
            server.execute(() -> prepare(2)); phase = 6; at = now; return;
        }
        if (phase == 6) {
            if (prepared != 2 || now - at < 250 || !mc.level.getBlockState(blocker).is(Blocks.STONE)) return;
            input.mouseButton(3, 1, 0); at = now; phase = 7; return;
        }
        if (phase == 7) {
            if (now - at < 250) return;
            require(mc.player.isUsingItem() && mc.player.getUseItem().is(Items.SHIELD), "nearer MC block keeps vanilla use path");
            input.mouseButton(3, 0, 0); server.execute(() -> prepare(3)); phase = 8; at = now; return;
        }
        if (phase == 8) {
            if (prepared != 3 || now - at < 250 || mc.player.getMainHandItem().getCount() != 8 || !mc.level.getBlockState(blocker).equals(oldBlock)) return;
            input.mouseButton(3, 1, 0); at = now; phase = 9; return;
        }
        if (phase == 9) {
            if (now - at < 450) return;
            input.mouseButton(3, 0, 0);
            require(mc.player.getMainHandItem().getCount() == 8 && !mc.player.isUsingItem(), "second native gesture still excludes vanilla side effects");
            require(doorClosed(), "second native use restores closed-door collision");
            checkDoorNormal(true);
            phase = 99;
            LoggerFactory.getLogger("sifucraft").info("[native-interaction-test] PASS: actual use gestures, no duplicate held use/no item or shield side effects, " +
                    "Shift and closer MC block priority, closed/open/closed dynamic collision and wall normal replacement");
        }
    }
    void restore(MinecraftServer server) {
        if (player == null || restored) return; restored = true;
        player.level().setBlockAndUpdate(blocker, oldBlock);
        player.setItemInHand(InteractionHand.MAIN_HAND, oldMain); player.setItemInHand(InteractionHand.OFF_HAND, oldOff);
        LoggerFactory.getLogger("sifucraft").info("[native-interaction-test] restored fixture inventory and blocker");
    }
}
