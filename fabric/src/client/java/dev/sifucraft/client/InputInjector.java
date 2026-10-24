package dev.sifucraft.client;

import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.client.mixin.MouseHandlerAccessor;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.KeyEvent;
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
        if (action == PRESS) heldKeys.add(scancode);
        if (action == RELEASE) heldKeys.remove(scancode);
        int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true);
        minecraft.keyboardHandler.keyPress(window(), action, new KeyEvent(scancode, keycode, modifiers));
        stats.keys++;
    }

    void mouseButton(int button, int action, int modifiers) {
        if (action == PRESS) heldButtons.add(button);
        if (action == RELEASE) heldButtons.remove(button);
        minecraft.mouseHandler.onButton(window(), new MouseButtonInfo(button, modifiers), action);
        stats.buttons++;
    }

    /** Ownership edge (event) or the snapshot's owner field disagreeing with ours (self-heal). */
    void setOwner(boolean minecraftOwns) {
        if (minecraftOwns == owner) return;
        owner = minecraftOwns;
        if (owner) {
            ((MouseHandlerAccessor) minecraft.mouseHandler).sifucraft$setMouseGrabbed(true);
        } else {
            releaseAll();
        }
    }

    /** Applies the mouse motion and wheel accumulated in Sifu since the previous frame. */
    void applyMotion(SifuFrame frame) {
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
