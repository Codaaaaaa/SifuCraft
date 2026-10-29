package dev.sifucraft.client;

import dev.sifucraft.bridge.SifuFrame;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Observe the real audio listener and normal attenuation metadata through MC/Native handovers. */
final class WorldAudioCheck implements AutoCloseable {
    private final Minecraft mc;
    private final Map<SoundSource, Float> volumes = new EnumMap<>(SoundSource.class);
    private final Map<SoundInstance, Integer> sounds = new IdentityHashMap<>();
    private final SoundEventListener listener;
    private boolean started, complete, segmentDone, registered;
    private String signature;
    private int segments, nativeSegments, samples, musicEvents, startedSounds, culledSounds;
    private long deadline, armAt;
    WorldAudioCheck(Minecraft minecraft) {
        mc = minecraft;
        listener = (sound, definition, range) -> {
            if (!started || complete) return;
            if (sound.getSource() == SoundSource.MUSIC) musicEvents++;
            if (!sounds.containsKey(sound)) return;
            sounds.put(sound, sounds.get(sound) + 1);
            require(!sound.isRelative() && sound.getAttenuation() == SoundInstance.Attenuation.LINEAR &&
                    Float.isFinite(range) && range > 0, "world sound must retain distance attenuation");
        };
    }
    private static void require(boolean ok, String text) { if (!ok) throw new AssertionError("world audio: " + text); }
    void tick(SifuFrame frame, boolean ready) {
        if (complete || !ready || frame == null || !mc.gameRenderer.mainCamera().isInitialized()) return;
        long now = System.currentTimeMillis();
        if (!started) {
            require(DevWorld.LEVEL_ID.startsWith("sifucraft-world-audio-check-"), "isolated save required");
            mc.getSoundManager().addListener(listener); registered = true;
            started = true; deadline = now + 45000;
            for (var source : SoundSource.values()) volumes.put(source, mc.options.getFinalSoundSourceVolume(source));
        }
        require(now < deadline, "six-stage listener handover deadline");
        String key = frame.inputOwner() + ":" + frame.anchorEpoch() + ":" + frame.cameraLocation() + ":" + frame.cameraRotation() + ":" + frame.cameraFov();
        if (!key.equals(signature)) { signature = key; segmentDone = false; armAt = now + 750; samples = 0; }
        if (now < armAt) return;
        var actual = mc.getSoundManager().getListenerTransform();
        Vec3 position, forward, up;
        var nativeView = SifuCraftClient.nativeWorldView();
        if (nativeView != null) {
            position = new Vec3(nativeView.x(), nativeView.y(), nativeView.z());
            var r = frame.cameraRotation(); double d = Math.PI / 180;
            double cp = Math.cos(r.x() * d), sp = Math.sin(r.x() * d), cy = Math.cos(r.y() * d), sy = Math.sin(r.y() * d);
            double cr = Math.cos(r.z() * d), sr = Math.sin(r.z() * d);
            forward = new Vec3(cp * cy, sp, cp * sy);
            up = new Vec3(-cr * sp * cy - sr * sy, cr * cp, cy * sr - cr * sp * sy);
        } else {
            var camera = mc.gameRenderer.mainCamera(); position = camera.position();
            var f = camera.forwardVector(); var u = camera.upVector();
            forward = new Vec3(f.x(), f.y(), f.z()); up = new Vec3(u.x(), u.y(), u.z());
        }
        require(actual.position().distanceTo(position) < .002 && actual.forward().distanceTo(forward) < .0002 &&
                actual.up().distanceTo(up) < .0002, "actual listener must follow current camera, including native roll");
        for (var source : SoundSource.values()) require(volumes.get(source).equals(mc.options.getFinalSoundSourceVolume(source)), "saved volume changed: " + source);
        if (++samples < 10 || segmentDone) return;
        segmentDone = true; segments++; if (!frame.minecraftOwnsInput()) nativeSegments++;
        var event = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.withDefaultNamespace("block.note_block.hat"));
        require(event != null, "test sound event");
        var right = forward.cross(up).normalize();
        var points = new Vec3[]{position.subtract(right.scale(2)), position.add(right.scale(2)), position.add(forward.scale(64))};
        for (int i = 0; i < points.length; i++) {
            var point = points[i];
            var sound = new SimpleSoundInstance(event, SoundSource.BLOCKS, .12f, 1, SoundInstance.createUnseededRandom(), point.x, point.y, point.z);
            sounds.put(sound, 0);
            var result = mc.getSoundManager().play(sound);
            require(sound.getSource() == SoundSource.BLOCKS && !sound.isRelative() && sound.getAttenuation() == SoundInstance.Attenuation.LINEAR, "spatial sound classification");
            require(Math.abs(point.distanceTo(position) - (i == 2 ? 64 : 2)) < .0001, "near/far sound position");
            if (result != SoundEngine.PlayResult.NOT_STARTED) { require(sounds.get(sound) == 1, "one notification per actual sound"); startedSounds++; }
            else { require(sounds.get(sound) <= 1, "culled/muted sound must not duplicate"); culledSounds++; }
        }
        // The real MusicManager entry must be suppressed while attached, in either ownership mode.
        mc.getMusicManager().startPlaying(new Music(Holder.direct(event), 0, 0, true));
        require(musicEvents == 0, "Minecraft music started while attached to Sifu");
        LoggerFactory.getLogger("sifucraft").info("[world-audio-test] segment={} owner={} epoch={} listener={} stable_samples={} positional_sounds={} culled_or_muted={} music_events={}",
                segments, frame.minecraftOwnsInput() ? "mc" : "sifu", frame.anchorEpoch(), actual.position(), samples, startedSounds, culledSounds, musicEvents);
        if (segments >= 6 && nativeSegments >= 3 && frame.minecraftOwnsInput() && frame.anchorEpoch() > 1) {
            complete = true; close();
            LoggerFactory.getLogger("sifucraft").info("[world-audio-test] PASS: six MC/native camera segments, actual listener position/forward/up including roll; left/right 2m and far 64m sounds retain attenuation and single delivery; music suppressed; volumes unchanged");
        }
    }
    @Override public void close() {
        if (!registered) return;
        registered = false;
        mc.getSoundManager().removeListener(listener);
        for (var sound : sounds.keySet()) mc.getSoundManager().stop(sound);
    }
}
