package dev.sifucraft.client;

import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.bridge.GuiCursor;
import dev.sifucraft.client.mixin.MouseHandlerAccessor;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Feeds the player's input, captured in Sifu, into Minecraft's own handlers exactly where SDL input
 * would arrive. Runs on the render thread.
 */
final class InputInjector {
    static final int PRESS = 1;
    static final int RELEASE = 0;

    /** Counters for the periodic log line. */
    static final class Stats {
        long keys;
        long buttons;
        long moves;
        double dxSum;
        double dySum;
        double scrollSum;
        long releaseAlls;
    }

    private final Minecraft minecraft;
    private final Stats stats = new Stats();
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Set<Integer> heldButtons = new HashSet<>();
    private boolean owner;
    private boolean haveTotals;
    private double lastDx;
    private double lastDy;
    private double lastScroll;
    private final GuiCursor cursor = new GuiCursor();
    private boolean guiMode;
    private double warpX = Double.NaN, warpY = Double.NaN;
    private int guiInputAck;
    private boolean haveGuiBaseline;
    private double guiBaselineX, guiBaselineY, guiBaselineScroll;

    InputInjector(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    boolean minecraftOwnsInput() {
        return owner;
    }

    Stats stats() {
        return stats;
    }

    void key(int scancode, int action, int modifiers) {
        syncMouseMode();
        boolean typing = guiMode;
        if (action == PRESS) heldKeys.add(scancode);
        if (action == RELEASE) heldKeys.remove(scancode);
        if (action == PRESS && useBinding() == scancode) SifuCraftClient.beginUseGesture();
        int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true);
        minecraft.keyboardHandler.keyPress(window(), action, new KeyEvent(scancode, keycode, modifiers));
        if (action == PRESS && useBinding() == scancode) raiseShieldImmediately();
        stats.keys++;
        syncMouseMode();
        // The hidden window receives no SDL text events. Forward printable characters for
        // container search fields; control/alt shortcuts remain ordinary key events.
        if (typing && guiMode && action != RELEASE && (modifiers & 0x0FC0) == 0 &&
                keycode >= 32 && keycode <= 0x10FFFF && !Character.isISOControl(keycode))
            minecraft.keyboardHandler.charTyped(window(), new CharacterEvent(keycode));
    }
    void key(int scancode, int action, int modifiers, int guiRevision) {
        key(scancode, action, modifiers);
        if (guiRevision != 0) guiInputAck = guiRevision;
    }
    int guiInputAck() { return guiInputAck; }
    int useBinding() {
        var key = ((dev.sifucraft.client.mixin.KeyMappingAccessor) minecraft.options.keyUse).sifucraft$key();
        if (minecraft.options.keyUse.isUnbound()) return 0;
        return switch (key.getType()) {
            case MOUSE -> 0x10000 | key.getValue();
            case KEYBOARD -> key.getValue();
        };
    }

    void mouseButton(int button, int action, int modifiers) {
        syncMouseMode();
        if (action == PRESS) heldButtons.add(button);
        if (action == RELEASE) heldButtons.remove(button);
        if (action == PRESS && useBinding() == (0x10000 | button)) SifuCraftClient.beginUseGesture();
        minecraft.mouseHandler.onButton(window(), new MouseButtonInfo(button, modifiers), action);
        if (action == PRESS && useBinding() == (0x10000 | button)) raiseShieldImmediately();
        stats.buttons++;
    }

    private void raiseShieldImmediately() {
        var player = minecraft.player;
        if (!owner || guiMode || minecraft.gui.screen() != null || minecraft.gui.overlay() != null ||
                minecraft.gameMode == null || player == null || !player.isAlive() || player.isUsingItem() ||
                (!player.getMainHandItem().has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS) &&
                 !player.getOffhandItem().has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS))) return;
        // Invoke the same path as the next client tick, including interaction and main-hand
        // priority. Consume its click once so the later tick does not perform it a second time.
        minecraft.options.keyUse.consumeClick();
        ((dev.sifucraft.client.mixin.MinecraftUseAccessor) minecraft).sifucraft$startUseItem();
    }

    /** Ownership edge (event) or the snapshot's owner field disagreeing with ours (self-heal). */
    void setOwner(boolean minecraftOwns) {
        if (minecraftOwns == owner) return;
        owner = minecraftOwns;
        haveGuiBaseline = false;
        if (owner) {
            syncMouseMode();
        } else {
            if (minecraft.player != null && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
                minecraft.player.closeContainer();
            releaseAll();
            syncMouseMode();
        }
        haveTotals = false;
    }

    void syncMouseMode() {
        boolean next = owner && SifuCraftClient.mirroredScreenActive();
        var mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
        if (next != guiMode) {
            guiMode = next; haveTotals = false; cursor.reset();
            if (next && haveGuiBaseline)
                cursor.move(guiBaselineX, guiBaselineY, guiBaselineScroll, SifuCraftClient.cursorReferenceWidth(), SifuCraftClient.cursorReferenceHeight());
            if (!next) haveGuiBaseline = false;
            if (next && !Double.isNaN(warpX)) cursor.warp(warpX, warpY);
            warpX = warpY = Double.NaN;
            // Create's value board opens while Use is still held and saves on its release: keep it held.
            if (!(next && SifuCraftClient.valueBoard(minecraft.gui.screen()))) releaseAll();
            mouse.sifucraft$setAccumulatedDX(0); mouse.sifucraft$setAccumulatedDY(0);
            mouse.sifucraft$setIgnoreFirstMove(false);
        }
        mouse.sifucraft$setMouseGrabbed(owner && !guiMode);
        if (guiMode) {
            var window = minecraft.getWindow();
            double x = cursor.x(window.getScreenWidth()), y = cursor.y(window.getScreenHeight());
            if (minecraft.mouseHandler.xpos() != x || minecraft.mouseHandler.ypos() != y) {
                minecraft.mouseHandler.onMove(window(), x, y, 0, 0);
                minecraft.mouseHandler.handleAccumulatedMovement();
            }
        }
    }

    /** A screen warped the (hidden) OS cursor to window pixels; mirror it on the Sifu pointer. */
    void warp(double px, double py) {
        var window = minecraft.getWindow();
        if (window.getScreenWidth() <= 0 || window.getScreenHeight() <= 0) return;
        double nx = px / window.getScreenWidth(), ny = py / window.getScreenHeight();
        if (!guiMode) { warpX = nx; warpY = ny; return; } // the board's init runs before the mode switch
        cursor.warp(nx, ny);
        minecraft.mouseHandler.onMove(window(), cursor.x(window.getScreenWidth()), cursor.y(window.getScreenHeight()), 0, 0);
        minecraft.mouseHandler.handleAccumulatedMovement();
    }

    void guiMotion(double x, double y, double scroll) {
        syncMouseMode();
        if (!owner) return;
        // E is consumed on a game tick. Its following cursor baseline can arrive before the
        // screen exists; retain it until the next mouse-mode transition instead of dropping it.
        guiBaselineX = x; guiBaselineY = y; guiBaselineScroll = scroll; haveGuiBaseline = true;
        if (!guiMode) return;
        var window = minecraft.getWindow();
        double wheel = cursor.move(x, y, scroll, SifuCraftClient.cursorReferenceWidth(), SifuCraftClient.cursorReferenceHeight());
        minecraft.mouseHandler.onMove(window(), cursor.x(window.getScreenWidth()), cursor.y(window.getScreenHeight()), 0, 0);
        minecraft.mouseHandler.handleAccumulatedMovement();
        if (wheel != 0) minecraft.mouseHandler.onScroll(window(), 0, wheel);
    }

    /** Applies the mouse motion and wheel accumulated in Sifu since the previous frame. */
    void applyMotion(SifuFrame frame) {
        syncMouseMode();
        if (guiMode) return; // ordered GUI events, rather than a newer snapshot, locate each click
        if (frame == null) {
            haveTotals = false;
            return;
        }
        if (!haveTotals) { // first frame after (re)connecting: take a baseline, apply nothing
            lastDx = frame.mouseDx();
            lastDy = frame.mouseDy();
            lastScroll = frame.scroll();
            haveTotals = true;
            return;
        }
        double dx = frame.mouseDx() - lastDx;
        double dy = frame.mouseDy() - lastDy;
        double scroll = frame.scroll() - lastScroll;
        lastDx = frame.mouseDx();
        lastDy = frame.mouseDy();
        lastScroll = frame.scroll();
        if (!owner) return;
        MouseHandler mouse = minecraft.mouseHandler;
        if (dx != 0 || dy != 0) {
            ((MouseHandlerAccessor) mouse).sifucraft$setMouseGrabbed(true);
            mouse.onMove(window(), mouse.xpos(), mouse.ypos(), dx, dy);
            stats.moves++;
            stats.dxSum += dx;
            stats.dySum += dy;
        }
        if (scroll != 0) {
            mouse.onScroll(window(), 0, scroll);
            stats.scrollSum += scroll;
        }
    }

    /** Lets go of everything Sifu pressed on Minecraft's side. */
    void releaseAll() {
        for (int scancode : Set.copyOf(heldKeys)) key(scancode, RELEASE, 0);
        for (int button : Set.copyOf(heldButtons)) mouseButton(button, RELEASE, 0);
        KeyMapping.releaseAll();
        stats.releaseAlls++;
    }

    private long window() {
        return minecraft.getWindow().handle();
    }
}
