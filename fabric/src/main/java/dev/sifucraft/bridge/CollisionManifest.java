package dev.sifucraft.bridge;

/**
 * The voxel grid Sifu currently publishes (the CollisionManifest region): a file to load whole.
 * {@link #NONE} until Sifu has one, and while no Sifu is connected.
 */
public record CollisionManifest(
        int generation, int worldGeneration, int blockCount, long cellCount, String map, String path) {

    public static final CollisionManifest NONE = new CollisionManifest(0, 0, 0, 0, "", "");
}
