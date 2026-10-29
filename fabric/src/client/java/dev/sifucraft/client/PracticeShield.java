package dev.sifucraft.client;

import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.LoggerFactory;

/** Optional manual-acceptance equipment for legacy development saves without a shield. */
final class PracticeShield {
    private static final String KEY = "sifucraft_practice_shield";
    private final String marker = UUID.randomUUID().toString();
    private boolean granted;

    void grantIfMissing(ServerPlayer player) {
        if (player.getMainHandItem().is(Items.SHIELD) || player.getOffhandItem().is(Items.SHIELD)) return;
        for (int i = 0; i < player.getInventory().getContainerSize(); ++i)
            if (player.getInventory().getItem(i).is(Items.SHIELD)) return;
        var shield = new ItemStack(Items.SHIELD);
        var tag = new CompoundTag(); tag.putString(KEY, marker);
        shield.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        shield.set(DataComponents.CUSTOM_NAME, Component.literal("练习盾"));
        if (player.getOffhandItem().isEmpty()) {
            player.setItemSlot(EquipmentSlot.OFFHAND, shield);
            granted = true;
        } else granted = player.getInventory().add(shield);
        if (granted) LoggerFactory.getLogger("sifucraft").info("[shield] temporary practice shield supplied; removed when this acceptance session closes");
    }

    private boolean owns(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && marker.equals(data.copyTag().getString(KEY).orElse(""));
    }

    void restore(MinecraftServer server, ServerPlayer player) {
        if (!granted) return;
        if (player != null) {
            if (owns(player.getUseItem())) player.stopUsingItem();
            for (int i = 0; i < player.getInventory().getContainerSize(); ++i)
                if (owns(player.getInventory().getItem(i))) player.getInventory().setItem(i, ItemStack.EMPTY);
            if (owns(player.getOffhandItem())) player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            if (owns(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
        }
        var drops = new java.util.ArrayList<ItemEntity>();
        for (var level : server.getAllLevels()) for (var entity : level.getAllEntities())
            if (entity instanceof ItemEntity item && owns(item.getItem())) drops.add(item);
        drops.forEach(ItemEntity::discard);
        granted = false;
        LoggerFactory.getLogger("sifucraft").info("[shield] temporary practice shield reclaimed");
    }
}
