package dev.sifucraft.client;

import dev.sifucraft.collision.SifuCollision;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import org.slf4j.LoggerFactory;

/** Real wind-charge use on a vanilla reference floor and on imported geometry, in a disposable save. */
final class WindChargeCheck {
    private ServerPlayer player;
    private ItemStack oldMain, oldOff;
    private BlockPos floor;
    private BlockState oldFloor;
    private Vec3 base;
    private volatile int prepared = -1;
    private int phase, index;
    private long pressedAt, deadline;
    private double peak, rise, referencePeak;
    private boolean restored;
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError("wind charge check: " + message); }

    private void prepare(int n) {
        player.setDeltaMovement(Vec3.ZERO); player.resetFallDistance();
        player.setIgnoreFallDamageFromCurrentImpulse(false, Vec3.ZERO);
        player.level().setBlockAndUpdate(floor, n == 0 ? Blocks.STONE.defaultBlockState() : oldFloor);
        player.teleportTo(player.level(), base.x, base.y, base.z, Set.of(), 0, 90, true);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WIND_CHARGE, 4));
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges(); prepared = n;
    }
    private void geometryControls() {
        var level = player.level();
        var active = SifuCollision.active();
        require(active != null && active.clip(base.add(0, 1, 0), base.add(0, -1, 0)) != null, "imported scene floor exists");
        require(oldFloor.isAir(), "real scene test has no vanilla floor block");
        var charge = new WindCharge(level, base.x, base.y + 3, base.z, new Vec3(0, -4, 0));
        charge.setDeltaMovement(0, -4, 0);
        var hit = ProjectileUtil.getHitResultOnMoveVector(charge, e -> false, ClipContext.Block.COLLIDER);
        require(hit.getType() == HitResult.Type.BLOCK, "wind-charge ray reaches imported floor");
        BlockPos nearer = floor.above(2); BlockState oldNearer = level.getBlockState(nearer);
        try {
            level.setBlockAndUpdate(nearer, Blocks.STONE.defaultBlockState());
            hit = ProjectileUtil.getHitResultOnMoveVector(charge, e -> false, ClipContext.Block.COLLIDER);
            require(hit.getType() == HitResult.Type.BLOCK && Math.abs(hit.getLocation().y - (nearer.getY() + 1)) < 1e-6,
                    "closer vanilla block retains priority over scene floor");
        } finally { level.setBlockAndUpdate(nearer, oldNearer); charge.discard(); }
        var snowball = EntityTypes.SNOWBALL.create(level, EntitySpawnReason.COMMAND);
        require(snowball != null, "snowball control exists");
        snowball.setPos(base.add(0, 3, 0)); snowball.setDeltaMovement(0, -4, 0);
        require(ProjectileUtil.getHitResultOnMoveVector(snowball, e -> false, ClipContext.Block.COLLIDER).getType() == HitResult.Type.MISS,
                "wind fix does not change other projectiles");
        snowball.discard();
    }
    void tick(Minecraft mc, InputInjector input, boolean ready) {
        if (phase == 99 || !ready || mc.player == null || mc.gui.screen() != null) return;
        long now = System.currentTimeMillis();
        require(deadline == 0 || now < deadline, "deadline phase=" + phase);
        var server = mc.getSingleplayerServer();
        if (phase == 0) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-wind-charge-check-"), "isolated world required");
            deadline = now + 25000; var id = mc.player.getUUID();
            server.execute(() -> {
                player = server.getPlayerList().getPlayer(id);
                oldMain = player.getMainHandItem().copy(); oldOff = player.getOffhandItem().copy(); base = player.position();
                floor = BlockPos.containing(base.x, base.y - .125, base.z); oldFloor = player.level().getBlockState(floor);
                geometryControls(); prepare(0);
            });
            phase = 1; return;
        }
        if (phase == 1) {
            if (prepared != index || !mc.player.getMainHandItem().is(Items.WIND_CHARGE) || mc.player.getMainHandItem().getCount() != 4 ||
                    Math.abs(mc.player.getY() - base.y) > .05 || mc.player.getCooldowns().isOnCooldown(mc.player.getMainHandItem())) return;
            mc.player.setXRot(90); mc.player.setYRot(0);
            input.mouseButton(3, 1, 0); input.mouseButton(3, 0, 0);
            pressedAt = now; peak = rise = -100; phase = 2; return;
        }
        peak = Math.max(peak, mc.player.getDeltaMovement().y); rise = Math.max(rise, mc.player.getY() - base.y);
        if (now - pressedAt < 1800) return;
        require(mc.player.getMainHandItem().getCount() == 3, "one real throw consumes one item");
        require(peak > .2 && rise > .2, "real throw boosts the player: peak=" + peak + " rise=" + rise);
        LoggerFactory.getLogger("sifucraft").info("[wind-charge-test] floor={} PASS count=3 peak={} rise={}", index == 0 ? "vanilla" : "imported", peak, rise);
        if (index == 0) {
            referencePeak = peak; index = 1; phase = 1; server.execute(() -> prepare(1));
        } else {
            require(Math.abs(peak - referencePeak) < .2, "scene-floor boost agrees with vanilla floor within launch spread");
            phase = 99;
            LoggerFactory.getLogger("sifucraft").info("[wind-charge-test] PASS: actual right-click use and single consumption, vanilla floor reference, imported floor collision/boost, closer vanilla priority, non-wind projectile control");
        }
    }
    void restore(MinecraftServer server) {
        if (player == null || restored) return;
        restored = true; player.level().setBlockAndUpdate(floor, oldFloor);
        player.setItemInHand(InteractionHand.MAIN_HAND, oldMain); player.setItemInHand(InteractionHand.OFF_HAND, oldOff);
        LoggerFactory.getLogger("sifucraft").info("[wind-charge-test] restored fixture items and floor");
    }
}
