package dev.sifucraft.client;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** Opt-in local development grants; game mode and abilities are never changed. */
final class DevItemAccess {
    private record Scope(MinecraftServer server, Object life, UUID player, Object screen, long session, int world, int epoch) { }
    private static volatile Scope current;
    private static final AtomicInteger queued = new AtomicInteger();
    private static final AtomicInteger logged = new AtomicInteger();

    static void update(Minecraft mc, long session, int world, int epoch, boolean ready) {
        current = ready && Boolean.getBoolean("sifucraft.devItems") && DevWorld.enabled() &&
                mc.player != null && mc.getSingleplayerServer() != null && mc.gui.screen() instanceof DevItemInventoryScreen
                ? new Scope(mc.getSingleplayerServer(), mc.player, mc.player.getUUID(), mc.gui.screen(), session, world, epoch) : null;
    }

    static void grant(DevItemInventoryScreen screen, ItemStack template, int count) {
        var mc = Minecraft.getInstance();
        Scope request = current;
        if (request == null || request.screen != screen || template.isEmpty() || count < 1 || count > template.getMaxStackSize()) return;
        if (queued.incrementAndGet() > 64) { queued.decrementAndGet(); return; }
        var stack = template.copyWithCount(count);
        request.server.execute(() -> {
            try {
                Scope now = current;
                if (now == null || now.server != request.server || now.life != request.life || now.screen != request.screen ||
                        now.session != request.session || now.world != request.world || now.epoch != request.epoch) return;
                var player = request.server.getPlayerList().getPlayer(request.player);
                if (player == null || !player.isAlive()) return;
                player.getInventory().add(stack);
                int given = count - stack.getCount();
                if (logged.getAndIncrement() < 100) org.slf4j.LoggerFactory.getLogger("sifucraft").info(
                        "[dev-items] granted {} x{} requested={} mode={}", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(template.getItem()), given, count, player.gameMode());
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
                mc.execute(() -> {
                    if (mc.player == request.life && mc.gui.screen() == screen)
                        screen.status = given == 0 ? "背包已满" : "已领取 " + given + (stack.isEmpty() ? "" : "（背包已满）");
                });
            } finally { queued.decrementAndGet(); }
        });
    }
}
