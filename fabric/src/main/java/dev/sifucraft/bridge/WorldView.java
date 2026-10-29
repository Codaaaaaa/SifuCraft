package dev.sifucraft.bridge;

import dev.sifucraft.collision.MapPlacement;

/** A render-only view of Sifu's camera. Never moves the Minecraft player or changes input. */
public record WorldView(double x, double y, double z, float yaw, float pitch, float roll, float verticalFov) {
    public static WorldView from(SifuFrame frame, MapPlacement placement, int width, int height) {
        if (frame == null || placement == null || !frame.hasCamera() || width <= 0 || height <= 0) return null;
        var p = frame.cameraLocation(); var r = frame.cameraRotation();
        float horizontalFov = frame.cameraFov();
        if (!Float.isFinite(p.x()) || !Float.isFinite(p.y()) || !Float.isFinite(p.z()) ||
                !Float.isFinite(r.x()) || !Float.isFinite(r.y()) || !Float.isFinite(r.z()) ||
                !Float.isFinite(horizontalFov) || horizontalFov <= 1 || horizontalFov >= 179) return null;
        float vertical = (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(horizontalFov) / 2) * height / width));
        return new WorldView(placement.mcX(p.x()), placement.mcY(p.z()), placement.mcZ(p.y()),
                (float) Math.IEEEremainder(r.y() - 90, 360), -r.x(), r.z(), vertical);
    }
}
