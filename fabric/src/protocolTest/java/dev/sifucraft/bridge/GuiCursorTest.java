package dev.sifucraft.bridge;

final class GuiCursorTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void run() {
        var cursor = new GuiCursor();
        cursor.move(1000, -800, 25, 1920, 1080);
        check(cursor.x(960) == 480 && cursor.y(540) == 270, "opening takes a baseline without replaying world movement");
        double wheel = cursor.move(1192, -692, 27, 1920, 1080);
        check(Math.abs(cursor.x(960) - 576) < .001 && Math.abs(cursor.y(540) - 324) < .001 && wheel == 2,
                "relative motion follows viewport coordinates at half-resolution capture");
        check(Math.abs(cursor.x(1920) - 1152) < .001, "resize preserves normalized pointer location");
        cursor.move(100000, 100000, 27, 1920, 1080);
        check(cursor.x(960) == 959 && cursor.y(540) == 539, "cursor clamps at the viewport edge");
        cursor.move(99904, 99946, 27, 1920, 1080);
        check(cursor.x(960) < 959 && cursor.y(540) < 539, "reverse movement leaves an edge immediately");
        double before = cursor.x(960);
        cursor.move(Double.NaN, 0, 0, 1920, 1080);
        check(cursor.x(960) == before, "nonfinite motion is rejected without corrupting the cursor");
        cursor.reset(); cursor.move(-9000, 9000, 0, 1920, 1080);
        check(cursor.x(960) == 480 && cursor.y(540) == 270, "handoff resets old-session cursor totals");
        McState state = McState.ready().withGui(true, true, 91);
        check((state.flags() & (Protocol.MC_GUI_OPEN | Protocol.MC_GUI_AVAILABLE)) ==
                (Protocol.MC_GUI_OPEN | Protocol.MC_GUI_AVAILABLE) && state.guiInputAck() == 91, "GUI state carries its input acknowledgement");
        check((state.withGui(false, true, 92).flags() & Protocol.MC_GUI_OPEN) == 0, "unavailable GUI cannot claim open input ownership");
        check(Protocol.McSnap.GUI_INPUT_ACK == 124 && Protocol.InputPayload.GUI_REVISION == 12 && Protocol.GUI_MOUSE_SIZE == 24,
                "golden native GUI wire offsets and event size");
        System.out.println("GUI cursor/ack tests PASS");
    }
}
