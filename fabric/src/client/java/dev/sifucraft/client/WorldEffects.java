package dev.sifucraft.client;

import dev.sifucraft.combat.EnemyEntities;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.slf4j.LoggerFactory;

/** Opt-in P7 rendering prototype. The ordinary P6 path remains the default. */
public final class WorldEffects {
    private static final boolean ENABLED = Boolean.getBoolean("sifucraft.worldEffects");
    private static long frames, nextLog;
    private static long probeReadyMs, probeNextMs;
    private WorldEffects() {}
    public static boolean active() { return ENABLED && SifuCraftClient.worldEffectsAvailable(); }
    static boolean configured() { return ENABLED; }
    public static void prepare(LevelRenderState state) {
        if (Boolean.getBoolean("sifucraft.pistonStressSelfTest")) PistonStressCheck.observe(state);
        if (Boolean.getBoolean("sifucraft.worldCracksSelfTest")) WorldCracksCheck.observe(state);
        long probeNow = System.currentTimeMillis();
        if (Boolean.getBoolean("sifucraft.worldProbe") && DevWorld.LEVEL_ID.startsWith("sifucraft-world-effects-check-native-")) {
            if (probeReadyMs == 0) probeReadyMs = probeNow + 1500;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && probeNow >= probeReadyMs && probeNow >= probeNextMs) {
                var center = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(1.5));
                int created = 0;
                for (int i = 0; i < 9; ++i) {
                    var particle = mc.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.END_ROD,
                            center.x + (i % 3 - 1) * .10, center.y + (i / 3 - 1) * .10, center.z, 0, 0, 0);
                    if (particle != null) { particle.setLifetime(160); mc.particleEngine.add(particle); ++created; }
                }
                probeNextMs = probeNow + 4000;
                LoggerFactory.getLogger("sifucraft").info("[world-probe] transient client-only particles={} center={}", created, center);
            }
        }
        // Vanilla owns the local model, equipment and animation. Keep it only in MC third
        // person: Sifu renders its own body after F8, and first person already has hands.
        var mc = net.minecraft.client.Minecraft.getInstance();
        boolean avatarVisible = SifuCraftClient.minecraftOwnsInput() && mc.player != null &&
                !mc.options.getCameraType().isFirstPerson();
        state.entityRenderStates.removeIf(entity -> entity.entityType == EnemyEntities.ENEMY_PROXY ||
                entity.entityType == dev.sifucraft.blocks.SignalEntities.PLAYER_BODY ||
                entity instanceof AvatarRenderState avatar && (!avatarVisible || avatar.id != mc.player.getId()));
        for (var entity : state.entityRenderStates) {
            entity.shadowPieces.clear();
            entity.outlineColor = 0;
            if (entity instanceof AvatarRenderState) { entity.nameTag = null; entity.scoreText = null; }
        }
        state.shouldShowEntityOutlines = false;
        if (Boolean.getBoolean("sifucraft.avatarSelfTest")) AvatarCheck.observe(state);
        frames++;
        long now = System.currentTimeMillis();
        if (now >= nextLog) {
            nextLog = now + 10000;
            LoggerFactory.getLogger("sifucraft").info("[world-effects] frames={} entities={} particle_groups={}",
                    frames, state.entityRenderStates.size(), state.particlesRenderState.particles.size());
        }
    }
}
