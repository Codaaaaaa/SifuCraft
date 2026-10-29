package dev.sifucraft.client;

import dev.sifucraft.bridge.Protocol;
import dev.sifucraft.combat.CombatHits;
import dev.sifucraft.combat.EnemyMirrors;
import java.util.ArrayList;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in native-Minecraft attack checks; also requires the fake endpoint's test world prefix. */
final class CombatCheck {
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private final ArrayList<CombatHits.Captured> hits = new ArrayList<>();
    private int tick;
    private int oldSlot;
    private ItemStack oldItem;

    void tick(MinecraftServer server) {
        var target = EnemyMirrors.testTarget(101);
        if (target == null || !CombatHits.canCapture(target) || server.getPlayerList().getPlayers().isEmpty()) return;
        var player = server.getPlayerList().getPlayers().getFirst();
        if (++tick == 1) {
            oldSlot = player.getInventory().getSelectedSlot();
            oldItem = player.getInventory().getItem(8).copy();
            player.getInventory().setItem(8, new ItemStack(Items.IRON_SWORD));
            player.getInventory().setSelectedSlot(8);
            player.connection.send(new ClientboundSetHeldSlotPacket(8));
            CombatHits.diagnostic(hits::add);
            LOG.info("[combat-test] preparing vanilla attack checks");
        }
        if (tick == 30) {
            LOG.info("[combat-test] weapon={} selected={} attack_damage={} strength={}", player.getMainHandItem(),
                    player.getInventory().getSelectedSlot(), player.getAttributeValue(Attributes.ATTACK_DAMAGE),
                    player.getAttackStrengthScale(0.5f));
            player.setSprinting(false); player.setOnGround(true); player.fallDistance = 0;
            player.attack(target);
            check(2);
            expect(0, 101, 6, 0);
            expect(1, 102, 1, Protocol.DAMAGE_SWEEP);
            player.attack(target);
            check(2); // reduced immediate second attack is rejected by vanilla invulnerability frames
        }
        if (tick == 60) {
            target.setHealth(1); // prove overkill is captured before local health clamping
            player.setOnGround(false); player.fallDistance = 1;
            player.attack(target);
            player.setOnGround(true); player.fallDistance = 0;
            check(3);
            expect(2, 101, 9, Protocol.DAMAGE_CRITICAL);
        }
        if (tick == 90) {
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
            sword.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK), 2);
            player.getInventory().setItem(8, sword);
        }
        if (tick == 120) {
            player.attack(target);
            check(5);
            expect(3, 101, 8, 0);
            expect(4, 102, 3, Protocol.DAMAGE_SWEEP);
            player.getInventory().setItem(8, oldItem);
            player.getInventory().setSelectedSlot(oldSlot);
            player.connection.send(new ClientboundSetHeldSlotPacket(oldSlot));
            CombatHits.diagnostic(null);
            LOG.info("[combat-test] PASS: vanilla normal, cooldown/invulnerability, sweep, critical overkill, " +
                    "Sharpness III + Knockback II, uniform Light stun; hits={}", hits);
        }
    }

    private void check(int count) {
        if (hits.size() != count) throw new AssertionError("combat hit count " + count + " expected, got " + hits);
    }
    private void expect(int index, int enemy, float amount, int flags) {
        var hit = hits.get(index);
        if (hit.enemyId() != enemy || Math.abs(hit.damage() - amount) > 0.001f || hit.flags() != flags ||
                Math.abs(hit.stun() - 0.15f) > 0.0001f) {
            throw new AssertionError("combat hit mismatch " + hit);
        }
    }
}
