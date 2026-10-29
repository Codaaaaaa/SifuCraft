package dev.sifucraft.combat;

import dev.sifucraft.bridge.PlayerHit;
import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.client.SifuCraftClient;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.slf4j.LoggerFactory;

/** Vanilla reference and actual bridge totem behavior, restricted to a disposable world. */
public final class TotemCheck {
    private int ticks, next, phase;
    private UUID id;
    private net.minecraft.server.level.ServerPlayer player;
    private ItemStack oldMain, oldOff;
    private float oldHealth, oldAbsorption;
    private GameType oldMode;
    private Map<String, String> reference;
    private volatile boolean wireSeen, animationSeen, animationExpired;
    private boolean restored;
    private int epoch;

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError("totem check: " + message); }
    private Map<String, String> effects() {
        var result = new TreeMap<String, String>();
        for (var effect : player.getActiveEffects()) result.put(effect.getEffect().toString(), effect.getDuration() + ":" + effect.getAmplifier());
        return result;
    }
    private void prepare(ItemStack main, ItemStack off) {
        player.stopUsingItem(); player.removeAllEffects(); player.setAbsorptionAmount(0); player.setHealth(20);
        player.setItemSlot(EquipmentSlot.MAINHAND, main); player.setItemSlot(EquipmentSlot.OFFHAND, off);
        player.setYRot(-90); player.setYHeadRot(-90);
    }
    private void bridged(EnemyProxy enemy, int flags) {
        ShieldBlocks.apply(player, SifuDamage.source(player, enemy, (flags & Protocol.HIT_CAN_DEFEND) != 0), 30,
                new PlayerHit(101, 1, 270, flags, 1, 1, -1, 0), 0, 0);
    }
    private void protectedLikeVanilla() {
        require(player.isAlive() && player.getHealth() == 1, "fatal hit leaves the same player alive at one HP");
        require(effects().equals(reference), "death protection effects match the unmodified vanilla reference");
    }

    public void tick(MinecraftServer server) {
        ++ticks;
        if (!SifuCraftClient.devItemsCheckComplete() || ticks < next || phase == 99) return;
        var enemy = EnemyMirrors.testTarget(101);
        if (enemy == null || !CombatHits.canCapture(enemy) || server.getPlayerList().getPlayers().isEmpty()) return;
        if (player == null) {
            require(System.getProperty("sifucraft.devWorldName", "").startsWith("sifucraft-items-totem-check-"), "isolated world required");
            player = server.getPlayerList().getPlayers().getFirst(); id = player.getUUID();
            oldMain = player.getMainHandItem().copy(); oldOff = player.getOffhandItem().copy();
            oldHealth = player.getHealth(); oldAbsorption = player.getAbsorptionAmount(); oldMode = player.gameMode();
            player.setGameMode(GameType.SURVIVAL);
            prepare(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.TOTEM_OF_UNDYING));
            epoch = EnemyMirrors.damageScope(enemy).epoch(); next = ticks + 60; return;
        }
        switch (phase++) {
            case 0 -> {
                player.hurtServer(player.level(), player.damageSources().mobAttack(enemy), 30);
                require(player.getOffhandItem().isEmpty() && player.getHealth() == 1, "vanilla off-hand totem reference");
                reference = effects(); require(!reference.isEmpty(), "vanilla totem adds effects");
                prepare(new ItemStack(Items.TOTEM_OF_UNDYING), new ItemStack(Items.SHIELD));
            }
            case 1 -> {
                bridged(enemy, 0); protectedLikeVanilla();
                require(player.getMainHandItem().isEmpty() && player.getOffhandItem().is(Items.SHIELD), "unblockable damage uses main-hand totem while preserving off-hand shield");
                prepare(new ItemStack(Items.TOTEM_OF_UNDYING), new ItemStack(Items.TOTEM_OF_UNDYING));
            }
            case 2 -> {
                bridged(enemy, Protocol.HIT_CAN_DEFEND); protectedLikeVanilla();
                require(player.getMainHandItem().isEmpty() && player.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "main-hand totem takes vanilla priority");
                bridged(enemy, Protocol.HIT_CAN_DEFEND);
                require(player.getHealth() == 1 && player.getOffhandItem().getCount() == 1, "hurt immunity prevents immediate second consumption");
                prepare(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.TOTEM_OF_UNDYING));
            }
            case 3 -> {
                bridged(enemy, Protocol.HIT_CAN_DEFEND); protectedLikeVanilla();
                require(player.getMainHandItem().is(Items.IRON_SWORD) && player.getOffhandItem().isEmpty(), "sword and off-hand totem coexist");
                prepare(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.TOTEM_OF_UNDYING));
            }
            case 4 -> {
                PlayerDamage.diagnostic(applied -> {
                    require(applied.before() == 20 && applied.after() == 1 && player.isAlive(), "native PlayerHit triggers totem before Minecraft death");
                    require(player.getOffhandItem().isEmpty() && player.getMainHandItem().is(Items.IRON_SWORD), "wire hit consumes exactly one off-hand totem");
                    require(server.getPlayerList().getPlayer(id) == player && EnemyMirrors.damageScope(enemy).epoch() == epoch, "totem does not replace player or request a respawn epoch");
                    protectedLikeVanilla(); wireSeen = true; phase = 5; next = ticks + 25;
                    PlayerDamage.diagnostic(null);
                });
                LoggerFactory.getLogger("sifucraft").info("[totem-test] direct PASS; READY for lethal native PlayerHit");
                phase = 80; return;
            }
            case 5 -> {
                require(wireSeen && animationSeen, "actual vanilla totem packet starts client activation animation");
                prepare(ItemStack.EMPTY, ItemStack.EMPTY); next = ticks + 25; return;
            }
            case 6 -> {
                require(animationExpired, "vanilla activation animation naturally finishes");
                player.hurtServer(player.level(), player.damageSources().mobAttack(enemy), 30);
                require(!player.isAlive(), "without a totem the same damage is fatal");
                phase = 99;
                LoggerFactory.getLogger("sifucraft").info("[totem-test] PASS: vanilla reference, main/off-hand priority, sword/shield compatibility, unblockable damage, effects and hurt immunity; native lethal hit consumes one totem at HP=1 without respawn; activation starts and finishes; no-totem control dies");
                return;
            }
            default -> { phase = 80; return; }
        }
        next = ticks + 60;
    }

    public void clientTick(Minecraft mc) {
        if (!wireSeen || mc.player == null) return;
        var activation = mc.player.itemActivation();
        if (activation.isActive() && activation.item().is(Items.TOTEM_OF_UNDYING)) animationSeen = true;
        if (animationSeen && !activation.isActive()) animationExpired = true;
    }

    public void restore(MinecraftServer server) {
        if (player == null || restored) return;
        restored = true; PlayerDamage.diagnostic(null);
        var current = server.getPlayerList().getPlayer(id);
        if (current != null) {
            current.removeAllEffects(); current.setGameMode(oldMode);
            current.setItemSlot(EquipmentSlot.MAINHAND, oldMain); current.setItemSlot(EquipmentSlot.OFFHAND, oldOff);
            current.setHealth(oldHealth); current.setAbsorptionAmount(oldAbsorption);
        }
        LoggerFactory.getLogger("sifucraft").info("[totem-test] restored fixture hands and player settings");
    }
}
