package dev.sifucraft.bridge;

import dev.sifucraft.collision.MapPlacement;

final class WorldViewTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static SifuFrame frame(SifuFrame.Vec3 position, SifuFrame.Vec3 rotation, float fov) {
        return new SifuFrame(1, 1, 2, Protocol.SNAPSHOT_HAS_CAMERA, SifuFrame.Vec3.ZERO,
                SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, position, rotation, fov, 20, 20,
                "test", 0, 0, 0, Protocol.OWNER_SIFU, 90, 3);
    }
    static void run() {
        var map = new MapPlacement("test", 10000, 64, -20000);
        var frame = frame(new SifuFrame.Vec3(125, -350, 275), new SifuFrame.Vec3(-25, 450, 17), 90);
        var view = WorldView.from(frame, map, 1920, 1080);
        check(view.x() == 10001.25 && view.y() == 66.75 && view.z() == -20003.5, "use actual native camera, including map offset and axis conversion");
        check(view.yaw() == 0 && view.pitch() == 25 && view.roll() == 17, "native rotation conversion and wrapped yaw");
        check(Math.abs(view.verticalFov() - 58.7155) < .001, "horizontal Unreal FOV becomes vertical Minecraft FOV");
        var portrait = WorldView.from(frame, map, 600, 1200);
        check(Math.abs(portrait.verticalFov() - 126.8699) < .001, "portrait resize preserves horizontal FOV");
        check(WorldView.from(frame, map, 0, 1080) == null && WorldView.from(frame, null, 1920, 1080) == null, "reject invalid viewport and absent map binding");
        check(WorldView.from(frame(SifuFrame.Vec3.ZERO, SifuFrame.Vec3.ZERO, Float.NaN), map, 1920, 1080) == null &&
                WorldView.from(frame(new SifuFrame.Vec3(Float.NaN, 0, 0), SifuFrame.Vec3.ZERO, 75), map, 1920, 1080) == null, "reject nonfinite native view");
        System.out.println("Native world view conversion PASS");
    }
}
