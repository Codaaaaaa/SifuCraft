package dev.sifucraft.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.slf4j.LoggerFactory;

/** Only profiles whose vanilla load found no saved data receive a starter inventory. */
public final class NewPlayerSetup {
    private static final Set<UUID> fresh = ConcurrentHashMap.newKeySet();
    private NewPlayerSetup() {}
    public static void reset() { fresh.clear(); }
    public static void loaded(UUID player, boolean existing) {
        if (existing) fresh.remove(player); else fresh.add(player);
    }
    static void joined(ServerPlayer player) {
        boolean first = fresh.remove(player.getUUID());
        if (first && !Boolean.getBoolean("sifucraft.inventorySelfTest")) {
            player.setGameMode(GameType.SURVIVAL);
            for (var stack : new ItemStack[]{new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SHIELD),
                    new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.COOKED_BEEF, 16),
                    new ItemStack(Items.STONE, 64), new ItemStack(Items.OAK_PLANKS, 64)}) {
                if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
            player.inventoryMenu.broadcastChanges();
        }
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) total += player.getInventory().getItem(i).getCount();
        LoggerFactory.getLogger("sifucraft").info("[inventory] profile={} starter={} mode={} items={}", player.getUUID(),
                first && !Boolean.getBoolean("sifucraft.inventorySelfTest") ? "granted-once" : "preserved", player.gameMode(), total);
    }
}
