package dev.sifucraft.client;

import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/** Chunk compilation policy: fluids never depend on the solid collision handover. */
public final class WorldTerrain {
    private static volatile int mode; // 0 vanilla, 1 fluids + native solids, 2 vanilla blocks + fluids
    private WorldTerrain() {}
    public static boolean fluidsOnly() { return mode == 1; }
    public static boolean hideBackingFloor() { return mode != 0; }
    static void update(Minecraft mc) {
        int wanted = !WorldEffects.active() ? 0 : SifuCraftClient.blockModelsActive() ? 2 : 1;
        if (mode == wanted) return;
        mode = wanted;
        // Compiled chunks cache the selected geometry. Rebuild when connecting, disconnecting,
        // or completing P8's handover, so old solid meshes cannot duplicate native actors.
        if (mc.level != null) mc.levelRenderer.invalidateCompiledGeometry(
                mc.level,mc.options,mc.gameRenderer.mainCamera(),mc.getBlockColors());
        LoggerFactory.getLogger("sifucraft").info("[world-terrain] mode={}",wanted == 0 ? "vanilla" : wanted == 1 ? "fluids" : "blocks+fluids");
    }
}
