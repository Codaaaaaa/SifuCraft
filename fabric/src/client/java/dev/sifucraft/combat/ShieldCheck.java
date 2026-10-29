package dev.sifucraft.combat;

import dev.sifucraft.bridge.PlayerHit;
import dev.sifucraft.bridge.Protocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.slf4j.LoggerFactory;

/** Opt-in checks against real vanilla item blocking; refuses the user's regular world. */
public final class ShieldCheck {
    private int phase, ticks, next, baselineWear, wireHits;
    private ServerPlayer player;
    private ItemStack oldMain, oldOff;
    private GameType oldMode;
    private float oldHealth, oldAbsorption;
    private boolean restored;
    private int resumeUseAt;
    private InteractionHand resumeHand;
    private volatile int animations;
    private volatile boolean animationRecovered;
    private int wireFinishedAt;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("shield check: " + message);
    }

    private void prepare(InteractionHand hand) {
        player.stopUsingItem();
        player.setHealth(20); player.setAbsorptionAmount(0);
        player.setYRot(-90); player.setYHeadRot(-90);
        player.setItemSlot(EquipmentSlot.MAINHAND, hand == InteractionHand.MAIN_HAND ? new ItemStack(Items.SHIELD) : oldMain.copy());
        player.setItemSlot(EquipmentSlot.OFFHAND, hand == InteractionHand.OFF_HAND ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
        player.startUsingItem(hand);
        // Replacing the stack invalidates the client's old active-item reference. Let that
        // slot update arrive, then raise through the real client packet path too.
        resumeHand = hand;
        resumeUseAt = ticks + 5;
    }

    private ShieldBlocks.Outcome apply(EnemyProxy target, int flags) {
        var hit = new PlayerHit(101, 1, 54, flags, 1, 1, -1, 0);
        LoggerFactory.getLogger("sifucraft").info("[shield-test] apply phase={} flags={} using={} blocking={} hand={} duration={} HP={} wear={}/{}", phase,
                flags, player.isUsingItem(), player.isBlocking(), player.getUsedItemHand(), player.getTicksUsingItem(), player.getHealth(),
                player.getMainHandItem().getDamageValue(), player.getOffhandItem().getDamageValue());
        return ShieldBlocks.apply(player, SifuDamage.source(player, target, (flags & Protocol.HIT_CAN_DEFEND) != 0), 6, hit, 0, 0);
    }

    public void tick(MinecraftServer server) {
        ++ticks;
        if (phase == 98) {
            if (ticks == wireFinishedAt + 8) net.minecraft.client.Minecraft.getInstance().execute(() -> {
                var clientPlayer = net.minecraft.client.Minecraft.getInstance().player;
                var state = new net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState();
                clientPlayer.firstPersonHandsAndItems().extractRenderState(clientPlayer, 1, state);
                require(animations == 3, "each actual wire block animates once, including immediate perfect");
                require(state.offHandHeight > .99f && clientPlayer.isUsingItem(), "vanilla shield height recovers while still holding use");
                animationRecovered = true;
            });
            if (animationRecovered) {
                LoggerFactory.getLogger("sifucraft").info("[shield-test] PASS: vanilla wear={}, front/back, unblockable, main/offhand ordinary/perfect, immediate perfect, shield break; Strong never disables; GuardBreaker ordinary/perfect/unblockable/just-raised cooldown + expiry; rear/lowered shield unaffected; wire hits={} animations={} with real hand lowering and recovery, including press+hit in one pump", baselineWear, wireHits, animations);
                PlayerDamage.diagnostic(null);
                ShieldBlockAnimation.diagnostic(null);
                phase = 99;
            }
            return;
        }
        if (resumeUseAt == ticks && !restored) {
            var hand = resumeHand;
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null && mc.gameMode != null) {
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, hand);
                }
            });
        }
        var target = EnemyMirrors.testTarget(101);
        if (target == null || !CombatHits.canCapture(target) || server.getPlayerList().getPlayers().isEmpty() || ticks < next || phase == 99) return;
        if (player == null) {
            require(System.getProperty("sifucraft.devWorldName", "").startsWith("sifucraft-shield-check-"), "use an isolated shield-check world");
            player = server.getPlayerList().getPlayers().getFirst();
            oldMain = player.getMainHandItem().copy(); oldOff = player.getOffhandItem().copy();
            oldMode = player.gameMode(); oldHealth = player.getHealth(); oldAbsorption = player.getAbsorptionAmount();
            player.setGameMode(GameType.SURVIVAL);
            prepare(InteractionHand.OFF_HAND);
            next = ticks + 25;
            return;
        }
        switch (phase++) {
            case 0 -> {
                player.setYHeadRot(-90);
                player.hurtServer(player.level(), player.damageSources().mobAttack(target), 6);
                baselineWear = player.getOffhandItem().getDamageValue();
                require(player.getHealth() == 20 && baselineWear > 0, "vanilla front block baseline");
                prepare(InteractionHand.OFF_HAND);
            }
            case 1 -> {
                player.setYHeadRot(-90); var result = apply(target, 3);
                require(result.hand() == InteractionHand.OFF_HAND, "ordinary block reports the actual off-hand for animation");
                require(player.getHealth() == 20 && player.getOffhandItem().getDamageValue() == baselineWear,
                        "ordinary block matches vanilla durability and HP: HP=" + player.getHealth() + " wear=" + player.getOffhandItem().getDamageValue() + " baseline=" + baselineWear);
                prepare(InteractionHand.OFF_HAND);
            }
            case 2 -> {
                player.setYHeadRot(90); var result = apply(target, 3);
                require(result.hand() == null, "back hit does not request a block animation");
                require(player.getHealth() == 14 && player.getOffhandItem().getDamageValue() == 0, "back hit is not blocked");
                prepare(InteractionHand.OFF_HAND);
            }
            case 3 -> {
                player.setYHeadRot(-90); var result = apply(target, 0);
                require(result.hand() == null, "unblockable hit does not request a block animation");
                require(player.getHealth() == 14 && player.getOffhandItem().getDamageValue() == 0, "Sifu unblockable hit bypasses a raised shield");
                prepare(InteractionHand.OFF_HAND);
                apply(target, Protocol.HIT_CAN_DEFEND | Protocol.HIT_PERFECT_WINDOW);
                require(player.getHealth() == 20 && player.getOffhandItem().getDamageValue() == 0, "perfect timing blocks immediately without durability loss");
                prepare(InteractionHand.MAIN_HAND);
            }
            case 4 -> {
                player.setYHeadRot(-90); var result = apply(target, 3);
                require(result.hand() == InteractionHand.MAIN_HAND, "main-hand block reports the actual hand for animation");
                require(player.getHealth() == 20 && player.getMainHandItem().getDamageValue() == baselineWear, "main-hand shield uses the same vanilla rule");
                prepare(InteractionHand.MAIN_HAND);
                apply(target, Protocol.HIT_CAN_DEFEND | Protocol.HIT_PERFECT_WINDOW);
                require(player.getHealth() == 20 && player.getMainHandItem().getDamageValue() == 0, "main-hand perfect block also preserves durability");
                prepare(InteractionHand.OFF_HAND);
            }
            case 5 -> {
                player.setYHeadRot(-90); apply(target, 0x203);
                require(player.getHealth() == 20 && player.getOffhandItem().getDamageValue() == baselineWear &&
                        !player.getCooldowns().isOnCooldown(player.getOffhandItem()), "ordinary Strong attack does not disable shield");
                prepare(InteractionHand.OFF_HAND);
                apply(target, 0x203 | Protocol.HIT_PERFECT_WINDOW);
                require(player.getHealth() == 20 && player.getOffhandItem().getDamageValue() == 0 &&
                        !player.getCooldowns().isOnCooldown(player.getOffhandItem()), "perfect Strong attack does not disable shield");
                prepare(InteractionHand.OFF_HAND);
            }
            case 6 -> {
                player.setYHeadRot(-90); apply(target, 0x203 | Protocol.HIT_GUARD_BREAKER);
                require(player.getHealth() == 20 && player.getOffhandItem().getDamageValue() == baselineWear &&
                        player.getCooldowns().isOnCooldown(player.getOffhandItem()) && !player.isUsingItem(), "ordinary GuardBreaker disables shield through vanilla axe rule");
                require(player.getCooldowns().getCooldownPercent(player.getOffhandItem(), 0) > .98f, "axe cooldown begins at five seconds");
                next = ticks + 110;
                return;
            }
            case 7 -> {
                require(!player.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD)), "axe cooldown naturally expires");
                prepare(InteractionHand.OFF_HAND);
                apply(target, 0x203 | Protocol.HIT_GUARD_BREAKER | Protocol.HIT_PERFECT_WINDOW);
                require(player.getHealth() == 20 && player.getOffhandItem().getDamageValue() == 0 &&
                        player.getCooldowns().isOnCooldown(player.getOffhandItem()) && !player.isUsingItem(), "perfect GuardBreaker also disables shield, without durability loss");
                next = ticks + 110;
                return;
            }
            case 8 -> {
                require(!player.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD)), "perfect GuardBreaker cooldown naturally expires");
                prepare(InteractionHand.OFF_HAND);
            }
            case 9 -> {
                apply(target, Protocol.HIT_GUARD_BREAKER);
                require(player.getHealth() == 14 && player.getOffhandItem().getDamageValue() == 0 &&
                        player.getCooldowns().isOnCooldown(player.getOffhandItem()) && !player.isUsingItem(), "unblockable GuardBreaker damages player and disables raised shield");
                next = ticks + 110;
                return;
            }
            case 10 -> {
                require(!player.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD)), "unblockable cooldown naturally expires");
                prepare(InteractionHand.OFF_HAND);
                apply(target, Protocol.HIT_GUARD_BREAKER);
                require(player.getHealth() == 14 && player.getCooldowns().isOnCooldown(player.getOffhandItem()), "unblockable GuardBreaker also disables a just-raised shield");
                next = ticks + 110;
                return;
            }
            case 11 -> {
                require(!player.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD)), "just-raised cooldown naturally expires");
                prepare(InteractionHand.OFF_HAND);
                player.setYHeadRot(90); apply(target, Protocol.HIT_GUARD_BREAKER);
                require(player.getHealth() == 14 && !player.getCooldowns().isOnCooldown(player.getOffhandItem()), "rear GuardBreaker bypasses shield without disabling it");
                prepare(InteractionHand.OFF_HAND);
            }
            case 12 -> {
                // Wait a normal interval after the previous unblocked hit so vanilla hurt
                // immunity cannot hide the lowered-shield case.
                player.stopUsingItem();
                apply(target, Protocol.HIT_GUARD_BREAKER);
                require(player.getHealth() == 14 && !player.getCooldowns().isOnCooldown(player.getOffhandItem()), "lowered shield is not disabled");
                prepare(InteractionHand.OFF_HAND);
                player.getOffhandItem().setDamageValue(player.getOffhandItem().getMaxDamage() - 1);
            }
            case 13 -> {
                player.setYHeadRot(-90); apply(target, 3);
                require(player.getHealth() == 20 && player.getOffhandItem().isEmpty(), "ordinary shield can break through vanilla durability handling");
                prepare(InteractionHand.OFF_HAND);
                apply(target, Protocol.HIT_CAN_DEFEND | Protocol.HIT_PARRIABLE | Protocol.HIT_GUARD_BREAKER);
                require(player.getHealth() == 14 && player.getCooldowns().isOnCooldown(player.getOffhandItem()), "GuardBreaker disables even inside vanilla raise delay without a perfect candidate");
                next = ticks + 110;
                return;
            }
            case 14 -> {
                require(!player.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD)), "raise-delay cooldown naturally expires");
                prepare(InteractionHand.OFF_HAND);
            }
            case 15 -> {
                ShieldBlockAnimation.diagnostic((block, clientPlayer) -> {
                    var state = new net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState();
                    clientPlayer.firstPersonHandsAndItems().extractRenderState(clientPlayer, 1, state);
                    require(block.hand() == InteractionHand.OFF_HAND && state.offHandHeight == 0,
                            "confirmed wire block lowers the actual shield hand using vanilla animation");
                    require(state.mainHandHeight > .99f && clientPlayer.isUsingItem(), "shield animation preserves other hand and continuous use");
                    ++animations;
                });
                PlayerDamage.diagnostic(applied -> {
                    ++wireHits;
                    require(applied.before() == 20 && applied.after() == 20, "native wire hit was blocked");
                    require(player.getOffhandItem().getDamageValue() == baselineWear, "wire ordinary then perfect durability");
                    if (wireHits == 2) {
                        require(!player.getCooldowns().isOnCooldown(player.getOffhandItem()), "native perfect Strong without GuardBreaker never disables shield");
                    }
                    if (wireHits == 3) {
                        require(!player.getCooldowns().isOnCooldown(player.getOffhandItem()), "fast perfect raise has no spurious cooldown");
                        wireFinishedAt = ticks;
                        phase = 98;
                    }
                });
                LoggerFactory.getLogger("sifucraft").info("[shield-test] direct PASS; READY for native wire hits; vanilla wear={}", baselineWear);
            }
            default -> { }
        }
        next = ticks + 25;
    }

    public void restore(MinecraftServer server) {
        if (player == null || restored) return;
        restored = true;
        PlayerDamage.diagnostic(null);
        ShieldBlockAnimation.diagnostic(null);
        player.stopUsingItem();
        player.setItemSlot(EquipmentSlot.MAINHAND, oldMain); player.setItemSlot(EquipmentSlot.OFFHAND, oldOff);
        player.setGameMode(oldMode); player.setHealth(oldHealth); player.setAbsorptionAmount(oldAbsorption);
        LoggerFactory.getLogger("sifucraft").info("[shield-test] restored fixture equipment and player settings");
    }
}
