package dev.sifucraft.ui;

/** Coverage of an outlined arrow in screen-pixel units; independent of Minecraft's GUI grid. */
public final class PointerRaster {
    public static final int WIDTH = 19, HEIGHT = 26;
    private static final double[] X = {0, 0, 5, 9, 13, 9, 18};
    private static final double[] Y = {0, 21, 16, 25, 23, 14, 14};
    private PointerRaster() {}

    /** Straight-alpha ARGB for Minecraft's GUI blend, with a one-pixel antialias transition. */
    public static int sample(double x, double y, double density) {
        double nearest = Double.POSITIVE_INFINITY;
        boolean inside = false;
        for (int a = 0, b = X.length - 1; a < X.length; b = a++) {
            double dx = X[b] - X[a], dy = Y[b] - Y[a];
            double t = Math.clamp(((x - X[a]) * dx + (y - Y[a]) * dy) / (dx * dx + dy * dy), 0, 1);
            double ex = x - X[a] - t * dx, ey = y - Y[a] - t * dy;
            nearest = Math.min(nearest, ex * ex + ey * ey);
            if ((Y[a] > y) != (Y[b] > y) && x < X[a] + (y - Y[a]) * dx / dy) inside = !inside;
        }
        double distance = Math.sqrt(nearest) * (inside ? -1 : 1);
        double alpha = Math.clamp(.5 - distance * density, 0, 1);
        int opacity = (int) Math.round(alpha * 255);
        if (opacity == 0) return 0;
        double white = Math.clamp(.5 - (distance + 1.25) * density, 0, 1);
        int gray = (int) Math.round(255 * white / alpha);
        return opacity << 24 | gray << 16 | gray << 8 | gray;
    }
}
