package dev.sifucraft.client;

import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.combat.CombatHits;
import dev.sifucraft.combat.EnemyMirrors;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Vanilla victim versus bridge victim, with real wind-burst motion packets in a disposable save. */
final class MaceCheck {
    private record Case(double fall, int density, int wind, boolean gliding) { }
    private static final Case[] CASES = {
        new Case(0, 0, 0, false), new Case(1.5, 0, 0, false), new Case(2, 0, 0, false),
        new Case(5, 0, 0, false), new Case(9, 0, 0, false), new Case(8, 5, 0, false),
        new Case(8, 0, 1, false), new Case(8, 0, 2, false), new Case(8, 0, 3, false),
        new Case(0, 0, 3, false), new Case(8, 0, 3, true)
    };
    private static final class Motion {
        final int index;
        final double baseY;
        volatile double peak = -100, rise = -100;
        Motion(int index, double baseY) { this.index = index; this.baseY = baseY; }
    }
    private volatile Motion motion;
    private volatile boolean clientReady;
    private ServerPlayer player;
    private ItemStack oldMain, oldOff;
    private GameType oldMode;
    private double oldSpeed;
    private Vec3 start;
    private int ticks, next, phase, index;
    private double previousWind;
    private boolean restored;
    private final ArrayList<CombatHits.Captured> hits = new ArrayList<>();
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError("mace check: " + message); }
    private static void near(double a, double b, String message) { require(Math.abs(a - b) < .001, message + ": " + a + " != " + b); }

    void clientTick(Minecraft mc, boolean ready) {
        clientReady = ready && mc.gui.screen() == null;
        Motion sample = motion;
        if (sample != null && clientReady && mc.player != null) {
            sample.peak = Math.max(sample.peak, mc.player.getDeltaMovement().y);
            sample.rise = Math.max(sample.rise, mc.player.getY() - sample.baseY);
        }
    }
    private void prepare(Case c) {
        motion = null;
        player.stopFallFlying(); player.resetFallDistance(); player.setDeltaMovement(Vec3.ZERO);
        player.setIgnoreFallDamageFromCurrentImpulse(false, Vec3.ZERO);
        player.teleportTo(player.level(), start.x, start.y, start.z, Set.of(), -90, 0, true);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        ItemStack mace = new ItemStack(Items.MACE);
        var registry = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        if (c.density > 0) mace.enchant(registry.getOrThrow(Enchantments.DENSITY), c.density);
        if (c.wind > 0) mace.enchant(registry.getOrThrow(Enchantments.WIND_BURST), c.wind);
        player.setItemInHand(InteractionHand.MAIN_HAND, mace);
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
    }
    private void attackState(Case c) {
        player.setDeltaMovement(new Vec3(0, c.fall > 0 ? -.6 : 0, 0));
        player.setOnGround(c.fall == 0); player.fallDistance = c.fall;
        if (c.gliding) player.startFallFlying(); else player.stopFallFlying();
        require(player.getAttackStrengthScale(.5f) > .99f, "reference and proxy use fully charged attacks");
        require(MaceItem.canSmashAttack(player) == (c.fall > 1.5 && !c.gliding), "vanilla smash threshold / gliding");
    }
    void serverTick(MinecraftServer server) {
        ++ticks;
        if (!clientReady || ticks < next || phase == 99) return;
        var target = EnemyMirrors.testTarget(101);
        var neighbor = EnemyMirrors.testTarget(102);
        if (target == null || neighbor == null || !CombatHits.canCapture(target) || server.getPlayerList().getPlayers().isEmpty()) return;
        if (player == null) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-mace-check-"), "isolated world required");
            player = server.getPlayerList().getPlayers().getFirst();
            oldMain = player.getMainHandItem().copy(); oldOff = player.getOffhandItem().copy(); oldMode = player.gameMode();
            oldSpeed = player.getAttribute(Attributes.ATTACK_SPEED).getBaseValue(); start = player.position();
            player.setGameMode(GameType.SURVIVAL);
            // A short fixture cooldown makes both consecutive vanilla calls fully charged.
            // Damage, victim defenses, enchantments and post-attack effects remain unmodified.
            player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(1000);
            prepare(CASES[0]); phase = 1; next = ticks + 8; return;
        }
        Case c = CASES[index];
        if (phase == 1) {
            Mob reference = new Mob(EntityTypes.ZOMBIE, player.level()) { };
            reference.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            reference.getAttribute(Attributes.ARMOR).setBaseValue(0);
            reference.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(0);
            reference.setHealth(1000); reference.setPos(target.position()); reference.setOnGround(target.onGround());
            attackState(c); player.attack(reference);
            float damage = 1000 - reference.getHealth();
            Vec3 expectedMotion = player.getDeltaMovement();
            reference.discard();
            attackState(c); // consecutive proxy attacks are separated by 32 vanilla ticks
            target.setDeltaMovement(Vec3.ZERO); neighbor.setDeltaMovement(Vec3.ZERO);
            hits.clear(); CombatHits.diagnostic(hits::add);
            try { player.attack(target); } finally { CombatHits.diagnostic(null); }
            require(hits.size() == 1 && hits.getFirst().enemyId() == 101, "one direct bridge victim, no range damage: " + hits);
            var hit = hits.getFirst(); near(hit.damage(), damage, "fully resolved damage matches vanilla victim");
            require(((hit.flags() & Protocol.DAMAGE_SMASH) != 0) == (c.fall > 1.5 && !c.gliding), "smash flag reflects actual damage type");
            near(hit.stun(), .15, "existing Light reaction retained");
            near(player.getDeltaMovement().distanceTo(expectedMotion), 0, "server impulse matches vanilla reference");
            if (c.fall > 1.5 && !c.gliding) {
                near(player.fallDistance, 0, "successful smash clears fall distance");
                require(hit.impulses().stream().anyMatch(p -> p.key().id() == 102), "vanilla mace range is captured for the second proxy");
            }
            else require(hit.impulses().isEmpty(), "ordinary and gliding attacks cannot authorize a range wave");
            near(neighbor.getDeltaMovement().lengthSqr(), 0, "range does not create independent proxy movement");
            LoggerFactory.getLogger("sifucraft").info("[mace-test] case={} fall={} density={} wind={} gliding={} damage={} flags={} serverMotion={} impulses={}",
                    index, c.fall, c.density, c.wind, c.gliding, damage, hit.flags(), player.getDeltaMovement(), hit.impulses());
            player.stopFallFlying(); motion = new Motion(index, start.y); phase = 2; next = ticks + 24; return;
        }
        Motion sample = motion;
        require(sample != null && sample.index == index && sample.peak > -100, "client observes real motion packets");
        if (c.wind > 0 && c.fall > 1.5 && !c.gliding) {
            require(sample.peak > .3 && sample.rise > .2, "Wind Burst raises the actual client");
            require(sample.peak > previousWind, "Wind Burst levels increase the actual impulse");
            previousWind = sample.peak;
        } else require(sample.peak <= .1, "no Wind Burst lift without enchantment / smash conditions; peak=" + sample.peak);
        near(neighbor.getDeltaMovement().lengthSqr(), 0, "Sifu retains proxy movement authority after range push");
        LoggerFactory.getLogger("sifucraft").info("[mace-test] case={} PASS clientPeak={} rise={}", index, sample.peak, sample.rise);
        if (++index == CASES.length) {
            motion = null; phase = 99;
            LoggerFactory.getLogger("sifucraft").info("[mace-test] PASS: vanilla victim damage at 0/1.5/2/5/9 blocks, Density V, actual smash flags and fall reset; Wind Burst I/II/III client lift increases, ordinary/gliding controls do not lift; actual range impulses captured, proxy movement remains native-owned");
        } else { prepare(CASES[index]); phase = 1; next = ticks + 8; }
    }
    void restore(MinecraftServer server) {
        if (player == null || restored) return;
        restored = true; motion = null; CombatHits.diagnostic(null);
        player.stopFallFlying(); player.resetFallDistance(); player.setDeltaMovement(Vec3.ZERO);
        player.setIgnoreFallDamageFromCurrentImpulse(false, Vec3.ZERO);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(oldSpeed); player.setGameMode(oldMode);
        player.setItemInHand(InteractionHand.MAIN_HAND, oldMain); player.setItemInHand(InteractionHand.OFF_HAND, oldOff);
        LoggerFactory.getLogger("sifucraft").info("[mace-test] restored fixture items, mode and attack speed");
    }
}
