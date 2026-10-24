package dev.sifucraft.combat;

import dev.sifucraft.bridge.McBridge;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.slf4j.LoggerFactory;

/** Confirmed server blocks drive vanilla first-person item lowering on the render thread. */
public final class ShieldBlockAnimation {
    private final ConcurrentLinkedQueue<PlayerDamage.Blocked> incoming = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private static volatile BiConsumer<PlayerDamage.Blocked, LocalPlayer> diagnostic; // opt-in client check
    private int logged;

    public ShieldBlockAnimation() {
        PlayerDamage.blockListener(block -> {
            if (queued.incrementAndGet() <= 128) incoming.add(block);
            else queued.decrementAndGet();
        });
    }

    public static void diagnostic(BiConsumer<PlayerDamage.Blocked, LocalPlayer> listener) { diagnostic = listener; }

    public void pump(Minecraft client, McBridge bridge, boolean ready) {
        PlayerDamage.Blocked block;
        while ((block = incoming.poll()) != null) {
            queued.decrementAndGet();
            var player = client.player;
            var frame = bridge.latest();
            if (!ready || !bridge.peerConnected() || frame == null || !frame.minecraftOwnsInput() ||
                    bridge.peerSession() != block.session() || frame.worldGeneration() != block.world() ||
                    frame.anchorEpoch() != block.epoch() || player == null || player != block.clientLife() || !player.isAlive() ||
                    client.gui.screen() != null || System.nanoTime() - block.receivedNs() > 500_000_000L ||
                    !player.isUsingItem() || player.getUsedItemHand() != block.hand()) continue;
            ItemStack use = player.getUseItem();
            ItemStack expected = block.item().copy();
            // The slot's durability update can arrive before or after this local notification.
            expected.setDamageValue(use.getDamageValue());
            if (!use.has(DataComponents.BLOCKS_ATTACKS) || player.getCooldowns().isOnCooldown(use) ||
                    !ItemStack.isSameItemSameComponents(use, expected)) continue;
            player.itemUsed(block.hand());
            if (logged++ < 200) LoggerFactory.getLogger("sifucraft").info("[shield] first-person block animation event={} hand={}",
                    block.eventId(), block.hand());
            if (diagnostic != null) diagnostic.accept(block, player);
        }
    }
}
