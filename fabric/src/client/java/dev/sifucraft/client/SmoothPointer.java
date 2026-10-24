package dev.sifucraft.client;

import dev.sifucraft.ui.PointerRaster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draw at capture-pixel resolution, keeping the hot spot independent of integer GUI coordinates. */
final class SmoothPointer {
    private SmoothPointer() {}
    static void draw(Minecraft mc, GuiGraphicsExtractor gui) {
        var window = mc.getWindow();
        double density = Math.min((double) window.getWidth() / SifuCraftClient.cursorReferenceWidth(),
                (double) window.getHeight() / SifuCraftClient.cursorReferenceHeight());
        density = Math.clamp(density, .1, 4);
        double hotX = mc.mouseHandler.xpos() * window.getWidth() / window.getScreenWidth();
        double hotY = mc.mouseHandler.ypos() * window.getHeight() / window.getScreenHeight();
        int left = (int) Math.floor(hotX) - 1, top = (int) Math.floor(hotY) - 1;
        int right = (int) Math.ceil(hotX + PointerRaster.WIDTH * density) + 1;
        int bottom = (int) Math.ceil(hotY + PointerRaster.HEIGHT * density) + 1;
        var pose = gui.pose();
        pose.pushMatrix();
        try {
            pose.scale(1f / Math.max(1, window.getGuiScale()));
            for (int y = top; y < bottom; y++) {
                int runStart = left, runColor = 0;
                for (int x = left; x <= right; x++) {
                    int color = x == right ? 0 : PointerRaster.sample((x + .5 - hotX) / density,
                            (y + .5 - hotY) / density, density);
                    if (color == runColor) continue;
                    if (runColor != 0) gui.fill(runStart, y, x, y + 1, runColor);
                    runStart = x; runColor = color;
                }
            }
        } finally { pose.popMatrix(); }
    }
}
