package dev.sifucraft.client;

import dev.sifucraft.bridge.SifuFrame;
import dev.sifucraft.collision.MapPlacement;

/**
 * The one place Minecraft and Unreal coordinates meet on the client.
 *
 * <p>Each Sifu map has a fixed place in the Minecraft world ({@link MapPlacement}): 1 block = 100 uu,
 * {@code UE.x = MC.x}, {@code UE.y = MC.z}, {@code UE.z = MC.y} plus the map's origin. Rotations:
 * {@code yaw_ue = yaw_mc + 90}, {@code pitch_ue = -pitch_mc}.
 *
 * <p>Each time Sifu hands the player to Minecraft (a new anchor epoch) Minecraft's player is put where
 * the pawn is now; the space counts as anchored to that epoch once the player has landed there.
 */
final class UnrealSpace {
    private MapPlacement placement;
    private boolean anchored;
    private int worldGeneration;
    private int anchorEpoch;

    /** Anchored to this Sifu world and hand-over. */
    boolean anchoredTo(SifuFrame sifu) {
        return anchored && worldGeneration == sifu.worldGeneration() && anchorEpoch == sifu.anchorEpoch();
    }
    boolean sameWorld(SifuFrame sifu){return anchored&&worldGeneration==sifu.worldGeneration();}
    boolean canRetainBoat(SifuFrame sifu,MapPlacement wanted,net.minecraft.world.phys.Vec3 seat){
        if(!sameWorld(sifu)||!sifu.hasPawn()||!sifu.minecraftOwnsInput()||!java.util.Objects.equals(placement,wanted)||seat==null||
            !Double.isFinite(seat.x)||!Double.isFinite(seat.y)||!Double.isFinite(seat.z)||!wanted.containsX((int)Math.floor(seat.x))||
            !Float.isFinite(sifu.capsuleHalfHeight())||sifu.capsuleHalfHeight()<=0)return false;
        var p=sifu.playerLocation();double dx=wanted.mcX(p.x())-seat.x,dz=wanted.mcZ(p.y())-seat.z,dy=wanted.mcY(p.z()-sifu.capsuleHalfHeight())-seat.y;
        return Double.isFinite(dx)&&Double.isFinite(dy)&&Double.isFinite(dz)&&dx*dx+dz*dz<=1.5*1.5&&Math.abs(dy)<=1;
    }

    int anchorEpoch() {
        return anchored ? anchorEpoch : 0;
    }

    MapPlacement placement() {
        return placement;
    }

    void reset() {
        anchored = false;
        placement = null;
    }

    void anchor(SifuFrame sifu, MapPlacement placement) {
        this.placement = placement;
        worldGeneration = sifu.worldGeneration();
        anchorEpoch = sifu.anchorEpoch();
        anchored = true;
    }

    SifuFrame.Vec3 toUnreal(double x, double y, double z) {
        return new SifuFrame.Vec3((float) placement.ueX(x), (float) placement.ueY(z), (float) placement.ueZ(y));
    }

    /** Blocks per tick -> cm per second, axes swapped like positions. */
    static SifuFrame.Vec3 velocityToUnreal(double x, double y, double z) {
        double scale = MapPlacement.CM_PER_BLOCK * 20.0;
        return new SifuFrame.Vec3((float) (x * scale), (float) (z * scale), (float) (y * scale));
    }

    /** (pitch, yaw, roll) in Unreal degrees. */
    static SifuFrame.Vec3 rotationToUnreal(float minecraftPitch, float minecraftYaw) {
        return new SifuFrame.Vec3(-minecraftPitch, wrapDegrees(minecraftYaw + 90f), 0f);
    }

    static float yawFromUnreal(float unrealYaw) {
        return wrapDegrees(unrealYaw - 90f);
    }

    /** The Sifu map of a world path: "Wuguan_Main" from "/Game/Maps/Wuguan/Wuguan_Main.Wuguan_Main". */
    static String mapName(String worldPath) {
        int dot = worldPath.lastIndexOf('.');
        return dot < 0 ? worldPath : worldPath.substring(dot + 1);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }
}
