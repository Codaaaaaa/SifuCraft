package dev.sifucraft.client;

import dev.sifucraft.combat.PlayerDamage;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.LoggerFactory;

/** Real held-use input against vanilla consumption, only in an explicitly disposable world. */
final class FoodCheck {
    private record Case(Item item, InteractionHand hand, int food, boolean consumes, boolean interrupt) { }
    private static final Case[] CASES = {
        new Case(Items.COOKED_BEEF, InteractionHand.MAIN_HAND, 10, true, false),
        new Case(Items.COOKED_BEEF, InteractionHand.MAIN_HAND, 20, false, false),
        new Case(Items.COOKED_BEEF, InteractionHand.MAIN_HAND, 10, false, true),
        new Case(Items.GOLDEN_APPLE, InteractionHand.MAIN_HAND, 20, true, false),
        new Case(Items.ENCHANTED_GOLDEN_APPLE, InteractionHand.OFF_HAND, 20, true, false),
        new Case(Items.GOLDEN_APPLE, InteractionHand.MAIN_HAND, 20, true, false)
    };
    private record Effect(int amplifier, int duration) { }
    private record Result(int count, int food, float saturation, float absorption, Map<String, Effect> effects) { }
    private volatile int requested = -1, prepared = -1, finished = -1, endCheck = -1;
    private volatile boolean regenRequested, regenDone, wireSeen;
    private ServerPlayer player;
    private ItemStack oldMain, oldOff;
    private float oldHealth, oldAbsorption, oldSaturation;
    private int oldFood, regenTicks;
    private boolean oldRegen, restored;
    private GameType oldMode;
    private Result reference;
    private int index, phase;
    private long next, deadline, pressedAt;
    private boolean useSeen, progressSeen, released;
    private boolean absorptionHudSeen;
    private int firstRemaining;
    private long nextDiagnostic;

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError("food check: " + message); }
    private static void near(float a, float b, String message) { require(Math.abs(a - b) < .001f, message + ": " + a + " != " + b); }
    private Result result(Case c) {
        var effects = new TreeMap<String, Effect>();
        for (var e : player.getActiveEffects()) effects.put(e.getEffect().toString(), new Effect(e.getAmplifier(), e.getDuration()));
        return new Result(player.getItemInHand(c.hand).getCount(), player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(), player.getAbsorptionAmount(), effects);
    }
    private void prepare(Case c) {
        player.stopUsingItem(); player.removeAllEffects(); player.setAbsorptionAmount(0); player.setHealth(12);
        player.getFoodData().setFoodLevel(c.food); player.getFoodData().setSaturation(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, c.hand == InteractionHand.MAIN_HAND ? new ItemStack(c.item, 3) : new ItemStack(Items.STICK));
        player.setItemInHand(InteractionHand.OFF_HAND, c.hand == InteractionHand.OFF_HAND ? new ItemStack(c.item, 3) : ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
    }
    private void compare(Result actual) {
        require(actual.count == reference.count && actual.food == reference.food, "count and nutrition match vanilla: " + actual + " / " + reference);
        near(actual.saturation, reference.saturation, "saturation matches vanilla");
        near(actual.absorption, reference.absorption, "absorption matches vanilla");
        require(actual.effects.keySet().equals(reference.effects.keySet()), "effect types match vanilla");
        for (var entry : reference.effects.entrySet()) {
            Effect a = actual.effects.get(entry.getKey()), b = entry.getValue();
            require(a.amplifier == b.amplifier && Math.abs(a.duration - b.duration) <= 2, "effect level/duration matches vanilla: " + entry.getKey());
        }
    }

    void serverTick(MinecraftServer server) {
        if (player == null || restored) return;
        if (regenRequested && !regenDone) {
            if (regenTicks++ == 0) {
                player.stopUsingItem(); player.removeAllEffects(); player.setAbsorptionAmount(0); player.setHealth(10);
                player.getFoodData().setFoodLevel(20); player.getFoodData().setSaturation(5);
            }
            if (regenTicks == 30) {
                near(player.getHealth(), 10, "natural regeneration disabled control");
                server.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, true, server);
            }
            if (regenTicks == 60) {
                require(player.getHealth() > 10, "vanilla full-food natural regeneration heals");
                server.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, false, server);
                regenDone = true;
                LoggerFactory.getLogger("sifucraft").info("[food-test] natural regeneration PASS: disabled control=10, enabled HP={}", player.getHealth());
            }
            return;
        }
        int n = requested;
        if (n < 0) return;
        Case c = CASES[n];
        if (prepared != n) {
            prepare(c);
            if (c.consumes) {
                ItemStack consumed = player.getItemInHand(c.hand).finishUsingItem(player.level(), player);
                player.setItemInHand(c.hand, consumed); reference = result(c); prepare(c);
            } else reference = result(c);
            prepared = n;
            LoggerFactory.getLogger("sifucraft").info("[food-test] prepared case={} main={} off={} food={}", n, player.getMainHandItem(), player.getOffhandItem(), player.getFoodData().getFoodLevel());
            return;
        }
        if (finished == n) return;
        if (c.consumes ? player.getItemInHand(c.hand).getCount() == 2 : endCheck == n) {
            compare(result(c));
            if (!c.consumes) require(!player.isUsingItem(), "blocked/interrupted use stops without consumption");
            if (n == CASES.length - 1) {
                player.removeEffect(MobEffects.REGENERATION); player.setHealth(20);
                near(player.getAbsorptionAmount(), 4, "wire fixture has golden apple absorption");
                PlayerDamage.diagnostic(applied -> {
                    require(applied.accepted() && applied.before() == 20 && applied.after() == 19, "wire hit applies 5 damage after 4 absorption");
                    near(player.getAbsorptionAmount(), 0, "wire hit consumes absorption first");
                    require(player.isAlive() && server.getPlayerList().getPlayer(player.getUUID()) == player, "wire hit preserves player life");
                    PlayerDamage.diagnostic(null); wireSeen = true;
                });
                LoggerFactory.getLogger("sifucraft").info("[food-test] READY for native 45 Sifu / 5 MC damage at HP=20 absorption=4");
            }
            finished = n;
            LoggerFactory.getLogger("sifucraft").info("[food-test] case={} item={} hand={} PASS vanilla={}", n, c.item, c.hand, reference);
        }
    }

    void tick(Minecraft mc, InputInjector input, boolean ready) {
        long now = System.currentTimeMillis();
        if (phase == 99 || !ready || mc.player == null || now < next || mc.gui.screen() != null) return;
        require(deadline == 0 || now < deadline, "deadline phase=" + phase + " case=" + index);
        var server = mc.getSingleplayerServer();
        if (phase == 0) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-food-check-"), "isolated world required");
            require(!Boolean.getBoolean("sifucraft.playerDamageTest"), "normal survival rules required");
            deadline = now + 80000;
            var id = mc.player.getUUID();
            server.execute(() -> {
                player = server.getPlayerList().getPlayer(id);
                oldMain = player.getMainHandItem().copy(); oldOff = player.getOffhandItem().copy();
                oldHealth = player.getHealth(); oldAbsorption = player.getAbsorptionAmount(); oldMode = player.gameMode();
                oldFood = player.getFoodData().getFoodLevel(); oldSaturation = player.getFoodData().getSaturationLevel();
                oldRegen = server.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
                server.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, false, server);
                player.setGameMode(GameType.SURVIVAL); requested = 0;
            });
            phase = 1; return;
        }
        Case c = CASES[index];
        if (phase == 1) {
            if (prepared != index || !mc.player.getItemInHand(c.hand).is(c.item) || mc.player.getItemInHand(c.hand).getCount() != 3 ||
                    mc.player.getFoodData().getFoodLevel() != c.food) return;
            mc.player.setYRot(90); mc.player.setXRot(-60); // keep the vanilla ray clear of fixture terrain/targets
            input.mouseButton(3, 1, 0); pressedAt = now; useSeen = progressSeen = released = false;
            require(mc.options.keyUse.isDown(), "right mouse maps to held use");
            phase = 2; return;
        }
        if (phase == 2) {
            if (now >= nextDiagnostic && now - pressedAt < 6000) {
                nextDiagnostic = now + 1000;
                LoggerFactory.getLogger("sifucraft").info("[food-test] input case={} down={} using={} main={} off={} food={} remaining={}", index,
                        mc.options.keyUse.isDown(), mc.player.isUsingItem(), mc.player.getMainHandItem(), mc.player.getOffhandItem(),
                        mc.player.getFoodData().getFoodLevel(), mc.player.getUseItemRemainingTicks());
            }
            if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == c.hand) {
                require(mc.player.getUseItem().get(DataComponents.CONSUMABLE).animation() == net.minecraft.world.item.ItemUseAnimation.EAT, "vanilla eating animation");
                int remaining = mc.player.getUseItemRemainingTicks();
                if (!useSeen) firstRemaining = remaining;
                else if (remaining < firstRemaining) progressSeen = true;
                useSeen = true;
            }
            if (c.interrupt && !released && now - pressedAt > 350) { input.mouseButton(3, 0, 0); released = true; }
            if (!c.consumes && now - pressedAt > 2400) {
                input.mouseButton(3, 0, 0); released = true; endCheck = index;
                if (c.interrupt) require(useSeen && progressSeen, "interrupted food actually began and advanced");
                else require(!useSeen, "full food rejects ordinary food");
            }
            if (finished != index) return;
            input.mouseButton(3, 0, 0);
            if (c.consumes) require(useSeen && progressSeen, "real held input advances vanilla eating animation");
            if (++index == 5) { regenRequested = true; phase = 3; }
            else if (index == CASES.length) { index--; phase = 4; }
            else { requested = index; phase = 1; }
            next = now + 350; return;
        }
        if (phase == 3 && regenDone) { requested = index; phase = 1; }
        if (phase == 4 && !wireSeen && mc.player.getAbsorptionAmount() == 4 && mc.player.hasEffect(MobEffects.ABSORPTION)) absorptionHudSeen = true;
        // Vanilla AbsorptionMobEffect removes itself on the first tick with no absorption left.
        if (phase == 4 && wireSeen && mc.player.getHealth() == 19 && mc.player.getAbsorptionAmount() == 0 && !mc.player.hasEffect(MobEffects.ABSORPTION)) {
            require(absorptionHudSeen, "golden apple absorption and effect reach the client before the wire hit");
            phase = 99;
            LoggerFactory.getLogger("sifucraft").info("[food-test] PASS: vanilla nutrition/saturation, full-food gate, interruption, full-food golden apple, off-hand enchanted apple effect types/levels/durations, real eating progress, natural regeneration; native hit consumes 4 absorption then 1 HP; client HUD state synchronized");
        }
    }

    void restore(MinecraftServer server) {
        if (player == null || restored) return;
        restored = true; PlayerDamage.diagnostic(null);
        server.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, oldRegen, server);
        player.stopUsingItem(); player.removeAllEffects(); player.setGameMode(oldMode);
        player.setItemInHand(InteractionHand.MAIN_HAND, oldMain); player.setItemInHand(InteractionHand.OFF_HAND, oldOff);
        player.setHealth(oldHealth); player.setAbsorptionAmount(oldAbsorption);
        player.getFoodData().setFoodLevel(oldFood); player.getFoodData().setSaturation(oldSaturation);
        LoggerFactory.getLogger("sifucraft").info("[food-test] restored fixture hands, health, mode, food and natural regeneration rule");
    }
}
