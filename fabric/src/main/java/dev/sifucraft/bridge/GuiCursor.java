package dev.sifucraft.bridge;

/** A clamped cursor driven by ordered cumulative motion, independent of the hidden SDL window. */
public final class GuiCursor {
    private double x = .5, y = .5, previousX, previousY, previousScroll;
    private boolean baseline;
    public void reset() { x = y = .5; baseline = false; }
    /** A screen placed the pointer itself (normalized 0..1); later motion stays relative to it. */
    public void warp(double nx, double ny) { if (Double.isFinite(nx) && Double.isFinite(ny)) { x = Math.clamp(nx, 0, 1); y = Math.clamp(ny, 0, 1); } }
    public double move(double totalX, double totalY, double scroll, int viewportWidth, int viewportHeight) {
        if (!Double.isFinite(totalX) || !Double.isFinite(totalY) || !Double.isFinite(scroll) ||
                viewportWidth <= 0 || viewportHeight <= 0) return 0;
        double wheel = 0;
        if (baseline) {
            x = Math.clamp(x + (totalX - previousX) / viewportWidth, 0, 1);
            y = Math.clamp(y + (totalY - previousY) / viewportHeight, 0, 1);
            wheel = scroll - previousScroll;
        }
        previousX = totalX; previousY = totalY; previousScroll = scroll; baseline = true;
        return wheel;
    }
    public double x(int width) { return Math.clamp(x * width, 0, Math.max(0, width - 1)); }
    public double y(int height) { return Math.clamp(y * height, 0, Math.max(0, height - 1)); }
}
