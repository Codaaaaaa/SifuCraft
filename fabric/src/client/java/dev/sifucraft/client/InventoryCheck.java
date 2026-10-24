package dev.sifucraft.client;

import dev.sifucraft.client.mixin.ContainerScreenAccessor;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

/** Opt-in vanilla container check; refuses to run in the user's normal development save. */
final class InventoryCheck {
    private static final Component TAG = Component.literal("SifuCraft inventory check");
    private int phase;
    private long next, deadline;
    private UUID playerId;
    private volatile boolean seeded, cleaned;
    private double totalX, totalY;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("inventory check: " + message);
    }
    void tick(Minecraft mc, InputInjector input, boolean ready) {
        long now = System.currentTimeMillis();
        if (phase == 99 || now < next) return;
        if (deadline != 0 && now > deadline) throw new AssertionError("inventory check timed out at phase " + phase);
        var player = mc.player;
        var server = mc.getSingleplayerServer();
        if (player == null || server == null || !ready) return;
        if (phase == 0 && mc.gui.screen() != null) return;
        if (phase == 0) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-ui-check"), "use an isolated sifucraft-ui-check save");
            deadline = now + 30000; playerId = player.getUUID();
            server.execute(() -> {
                var p = server.getPlayerList().getPlayer(playerId);
                require(p != null && p.getInventory().getItem(9).isEmpty() && p.getInventory().getItem(10).isEmpty() &&
                        p.getInventory().getItem(0).isEmpty(), "fixture slots must be empty");
                var stack = new ItemStack(Items.DIRT, 12); stack.set(DataComponents.CUSTOM_NAME, TAG);
                p.getInventory().setItem(9, stack); seeded = true;
            });
            phase++; return;
        }
        if (!seeded || player.getInventory().getItem(9).isEmpty() && phase == 1) return;
        var inv = player.getInventory();
        LoggerFactory.getLogger("sifucraft").info("[inventory-test] phase={} screen={} carried={}", phase,
                mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(), player.containerMenu.getCarried().getCount());
        switch (phase) {
            case 1 -> press(input, 8);
            case 2 -> { require(SifuCraftClient.mirroredScreenActive(), "inventory remains visible"); input.guiMotion(0, 0, 0); click(mc, input, 9, 1, 0); }
            case 3 -> { require(player.containerMenu.getCarried().getCount() == 12 && inv.getItem(9).isEmpty(), "left pickup"); click(mc, input, 10, 1, 0); }
            case 4 -> { require(inv.getItem(10).getCount() == 12 && player.containerMenu.getCarried().isEmpty(), "left placement"); click(mc, input, 10, 3, 0); }
            case 5 -> { require(inv.getItem(10).getCount() == 6 && player.containerMenu.getCarried().getCount() == 6, "right split"); click(mc, input, 9, 1, 0); }
            case 6 -> { require(inv.getItem(9).getCount() == 6, "split placement"); move(mc, input, 9); press(input, 30); }
            case 7 -> { require(inv.getItem(0).getCount() == 6 && inv.getItem(9).isEmpty(), "number-key hotbar swap"); press(input, 30); }
            case 8 -> { require(inv.getItem(9).getCount() == 6 && inv.getItem(0).isEmpty(), "swap back"); click(mc, input, 9, 1, 1); }
            case 9 -> {
                int count = 0; for (int i = 0; i < 9; i++) if (TAG.equals(inv.getItem(i).get(DataComponents.CUSTOM_NAME))) count += inv.getItem(i).getCount();
                require(count == 6 && inv.getItem(9).isEmpty(), "shift quick move"); press(input, 41);
            }
            case 10 -> { require(mc.gui.screen() == null && player.containerMenu.getCarried().isEmpty(), "Escape closes without a MC pause screen"); press(input, 8); }
            case 11 -> {
                require(SifuCraftClient.mirroredScreenActive(), "E reopens");
                click(mc, input, 10, 1, 0);
                require(player.containerMenu.getCarried().getCount() == 6, "pick up before drag");
                move(mc, input, 9); input.mouseButton(1, 1, 0);
                totalX += 1; input.guiMotion(totalX, totalY, 0); // drag through the first slot before the second
                move(mc, input, 11); input.mouseButton(1, 0, 0);
                require(inv.getItem(9).getCount() == 3 && inv.getItem(11).getCount() == 3 &&
                        player.containerMenu.getCarried().isEmpty(), "drag distributes across two slots");
            }
            case 12 -> {
                click(mc, input, 9, 1, 0);
                require(player.containerMenu.getCarried().getCount() == 3, "hold an item during handoff");
                input.setOwner(false);
            }
            case 13 -> {
                require(mc.gui.screen() == null && player.containerMenu.getCarried().isEmpty(), "ownership loss closes and releases the carried stack");
                int total = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) if (TAG.equals(inv.getItem(i).get(DataComponents.CUSTOM_NAME))) total += inv.getItem(i).getCount();
                require(total == 12, "handoff returns carried items without loss or duplication");
                server.execute(() -> restore(server));
            }
            case 14 -> {
                if (!cleaned) return;
                press(input, 8);
                final EditBox[] probe = new EditBox[1];
                mc.gui.setScreen(new InventoryScreen(player) {
                    @Override protected void init() {
                        super.init();
                        probe[0] = addRenderableWidget(new EditBox(font, 8, 8, 120, 20, Component.literal("Input check")));
                        setFocused(probe[0]);
                    }
                });
                for (int code : new int[]{7, 12, 21, 23}) { input.key(code, 1, 0); input.key(code, 0, 0); }
                require(probe[0].getValue().equals("dirt"), "container text receives forwarded characters");
                input.setOwner(false);
                require(SifuCraftClient.guiWireEvents() > 0, "native GUI motion event traversed the bridge");
                phase = 99;
                LoggerFactory.getLogger("sifucraft").info("[inventory-test] PASS: real vanilla inventory; left pickup/place, right split, number swap, shift quick move, drag distribution, text input, Escape/E and carried-item handoff without loss/duplication; fixture removed");
                return;
            }
            default -> throw new AssertionError(phase);
        }
        phase++; next = now + 450;
    }
    private void press(InputInjector input, int key) {
        boolean opening = key == 8 && !SifuCraftClient.mirroredScreenActive();
        input.key(key, 1, 0, 100 + phase); input.key(key, 0, 0);
        // Native E-open is followed by its ordered cumulative-motion baseline.
        if (opening) input.guiMotion(totalX, totalY, 0);
    }
    private void click(Minecraft mc, InputInjector input, int inventorySlot, int button, int modifiers) {
        move(mc, input, inventorySlot);
        input.mouseButton(button, 1, modifiers); input.mouseButton(button, 0, modifiers);
    }
    private void move(Minecraft mc, InputInjector input, int inventorySlot) {
        var screen = (AbstractContainerScreen<?>) mc.gui.screen();
        var origin = (ContainerScreenAccessor) screen;
        var slot = screen.getMenu().slots.stream().filter(s -> s.container == mc.player.getInventory() && s.getContainerSlot() == inventorySlot)
                .findFirst().orElseThrow();
        var window = mc.getWindow();
        double x = (origin.sifucraft$left() + slot.x + 8.0) * window.getScreenWidth() / window.getGuiScaledWidth();
        double y = (origin.sifucraft$top() + slot.y + 8.0) * window.getScreenHeight() / window.getGuiScaledHeight();
        totalX += (x - mc.mouseHandler.xpos()) * SifuCraftClient.cursorReferenceWidth() / window.getScreenWidth();
        totalY += (y - mc.mouseHandler.ypos()) * SifuCraftClient.cursorReferenceHeight() / window.getScreenHeight();
        input.guiMotion(totalX, totalY, 0);
    }
    void restore(MinecraftServer server) {
        if (playerId == null) return;
        var player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++)
                if (TAG.equals(player.getInventory().getItem(i).get(DataComponents.CUSTOM_NAME))) player.getInventory().setItem(i, ItemStack.EMPTY);
            if (TAG.equals(player.containerMenu.getCarried().get(DataComponents.CUSTOM_NAME))) player.containerMenu.setCarried(ItemStack.EMPTY);
        }
        cleaned = true;
    }
}
