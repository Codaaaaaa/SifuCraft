package dev.sifucraft.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.slf4j.LoggerFactory;

/** Real catalog, input and integrated-server grants in a disposable world. */
final class DevItemCheck {
    private int phase;
    private long next, deadline;
    private volatile boolean saved, restored;
    private List<ItemStack> inventory;
    private DevItemInventoryScreen screen;
    private int dirtBefore, totemsBefore;
    static volatile boolean complete;

    private static void require(boolean value, String message) { if (!value) throw new AssertionError("dev item check: " + message); }
    private static int count(net.minecraft.world.entity.player.Player p, net.minecraft.world.item.Item item) {
        int n = 0; for (int i = 0; i < p.getInventory().getContainerSize(); ++i) if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
        return n;
    }
    private static void press(InputInjector input, int key) { input.key(key, 1, 0); input.key(key, 0, 0); }

    void tick(Minecraft mc, InputInjector input, boolean ready) {
        long now = System.currentTimeMillis();
        if (complete || now < next || !ready || mc.player == null) return;
        if (deadline == 0) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-items-totem-check-"), "isolated world required");
            deadline = now + 30000;
        }
        require(now < deadline, "deadline phase=" + phase);
        var server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        switch (phase) {
            case 0 -> {
                if (mc.gui.screen() != null) return;
                dirtBefore = count(mc.player, Items.DIRT); totemsBefore = count(mc.player, Items.TOTEM_OF_UNDYING);
                server.execute(() -> {
                    var p = server.getPlayerList().getPlayer(id);
                    inventory = java.util.stream.IntStream.range(0, p.getInventory().getContainerSize()).mapToObj(i -> p.getInventory().getItem(i).copy()).toList();
                    require(p.gameMode() == GameType.SURVIVAL, "server starts in survival"); saved = true;
                });
            }
            case 1 -> { if (!saved) return; press(input, 8); }
            case 2 -> {
                require(mc.gui.screen() instanceof DevItemInventoryScreen, "E opens survival inventory with catalog");
                screen = (DevItemInventoryScreen) mc.gui.screen();
                var box = (EditBox) screen.children().stream().filter(EditBox.class::isInstance).findFirst().orElseThrow();
                box.setValue(""); screen.setFocused(box);
                for (int key : new int[]{23, 18, 23, 8, 16}) press(input, key);
                require(box.getValue().equals("totem") && mc.gui.screen() == screen, "search accepts E without closing");
            }
            case 3 -> {
                click(mc, input, 3);
                require(!mc.player.getAbilities().instabuild && !mc.player.getAbilities().invulnerable, "catalog does not enable creative abilities");
                server.execute(() -> require(server.getPlayerList().getPlayer(id).gameMode() == GameType.SURVIVAL, "server stays survival while catalog is open"));
            }
            case 4 -> {
                require(count(mc.player, Items.TOTEM_OF_UNDYING) == totemsBefore + 1, "catalog grants a real totem");
                ((EditBox) screen.children().stream().filter(EditBox.class::isInstance).findFirst().orElseThrow()).setValue("minecraft:dirt");
                click(mc, input, 1);
            }
            case 5 -> {
                require(count(mc.player, Items.DIRT) == dirtBefore + 64, "left click grants one stack; count=" + count(mc.player, Items.DIRT) + " status=" + screen.status);
                click(mc, input, 3);
            }
            case 6 -> {
                require(count(mc.player, Items.DIRT) == dirtBefore + 65, "right click grants one item");
                ((EditBox) screen.children().stream().filter(EditBox.class::isInstance).findFirst().orElseThrow()).setValue("wind_burst");
                click(mc, input, 3);
            }
            case 7 -> {
                boolean book = false;
                for (int i = 0; i < mc.player.getInventory().getContainerSize(); ++i) {
                    var stack = mc.player.getInventory().getItem(i);
                    if (stack.is(Items.ENCHANTED_BOOK) && stack.getComponents().toString().contains("wind_burst")) book = true;
                }
                require(book, "creative variants retain enchantment components");
                press(input, 41);
            }
            case 8 -> {
                require(mc.gui.screen() == null && !mc.player.getAbilities().instabuild, "Escape closes and survival abilities remain");
                DevItemAccess.grant(screen, new ItemStack(Items.DIRT), 64);
            }
            case 9 -> {
                require(count(mc.player, Items.DIRT) == dirtBefore + 65, "closed catalog cannot grant stale requests");
                server.execute(() -> {
                    var p = server.getPlayerList().getPlayer(id);
                    for (int i = 0; i < inventory.size(); ++i) p.getInventory().setItem(i, inventory.get(i));
                    p.inventoryMenu.broadcastChanges(); restored = true;
                });
            }
            case 10 -> {
                if (!restored) return;
                complete = true;
                LoggerFactory.getLogger("sifucraft").info("[dev-items-test] PASS: real E/search including E, left stack/right single, totem and enchanted variants, survival on both sides, closed request rejected; fixture inventory restored");
                return;
            }
            default -> throw new AssertionError(phase);
        }
        ++phase; next = now + 450;
    }

    private void click(Minecraft mc, InputInjector input, int button) {
        double x = (screen.catalogX + 12.0) * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
        double y = (screen.gridY + 8.0) * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight();
        double dx = (x - mc.mouseHandler.xpos()) * SifuCraftClient.cursorReferenceWidth() / mc.getWindow().getScreenWidth();
        double dy = (y - mc.mouseHandler.ypos()) * SifuCraftClient.cursorReferenceHeight() / mc.getWindow().getScreenHeight();
        totalX += dx; totalY += dy;
        input.guiMotion(totalX, totalY, 0);
        input.mouseButton(button, 1, 0); input.mouseButton(button, 0, 0);
    }
    private double totalX, totalY;
}
