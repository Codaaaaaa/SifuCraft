package dev.sifucraft.collision;

import dev.sifucraft.bridge.CollisionManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Keeps the grid Sifu's manifest names: when a new generation appears, loads its file on a
 * background thread, checks it against the manifest's counts and swaps it in. The grid only ever
 * changes as a whole, so readers on any thread see either the old one or the new one.
 */
public final class CollisionLoader {
    private final Consumer<String> log;
    private final Object lock = new Object();
    private final Thread thread;
    private CollisionManifest wanted = CollisionManifest.NONE; // guarded by lock
    private boolean closed;                                     // guarded by lock
    private volatile CollisionGrid grid;
    private volatile int loadedGeneration;
    private int failedGeneration;                               // loader thread only

    public CollisionLoader(Consumer<String> log) {
        this.log = log;
        thread = Thread.ofPlatform().daemon().name("sifucraft-collision").start(this::run);
    }

    /** Called with the current manifest (any thread, typically every frame). */
    public void update(CollisionManifest manifest) {
        synchronized (lock) {
            if (manifest.generation() == wanted.generation()) return;
            if (manifest.worldGeneration() != wanted.worldGeneration() || !manifest.map().equals(wanted.map())) {
                grid = null; loadedGeneration = 0;
            }
            wanted = manifest;
            if (manifest.generation() == 0) {
                grid = null;
                loadedGeneration = 0;
            }
            lock.notifyAll();
        }
    }

    /** The loaded grid, or null. */
    public CollisionGrid grid() {
        return grid;
    }

    /** The manifest generation of {@link #grid()} (0 = none). */
    public int loadedGeneration() {
        return loadedGeneration;
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    private void run() {
        while (true) {
            CollisionManifest next;
            synchronized (lock) {
                while (!closed && (wanted.generation() == 0 || wanted.generation() == loadedGeneration
                        || wanted.generation() == failedGeneration)) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed) return;
                next = wanted;
            }
            load(next);
        }
    }

    private void load(CollisionManifest manifest) {
        long start = System.nanoTime();
        try {
            if(Boolean.getBoolean("sifucraft.fluidSceneSelfTest")){
                long delay=Long.getLong("sifucraft.fluidGeometryLoadDelayMs",0);
                if(delay>0&&delay<=2000)try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            }
            CollisionGrid loaded = CollisionGrid.read(Path.of(manifest.path()));
            if (loaded.blockCount() != manifest.blockCount() || loaded.cellCount() != manifest.cellCount()) {
                failedGeneration = manifest.generation();
                log.accept(String.format("grid %s (%s) does NOT match its manifest: %d blocks / %d cells, expected %d / %d",
                        Integer.toUnsignedString(manifest.generation()), manifest.map(), loaded.blockCount(), loaded.cellCount(),
                        manifest.blockCount(), manifest.cellCount()));
                return;
            }
            synchronized (lock) {
                if (wanted.generation() != manifest.generation()) return; // superseded meanwhile
                grid = loaded;
                loadedGeneration = manifest.generation();
            }
            log.accept(String.format("loaded grid %s: %s (Sifu world %d), %d blocks, %d cells, matches the manifest, in %d ms",
                    Integer.toUnsignedString(manifest.generation()), loaded.map(), manifest.worldGeneration(), loaded.blockCount(),
                    loaded.cellCount(), (System.nanoTime() - start) / 1_000_000));
        } catch (IOException | RuntimeException e) {
            failedGeneration = manifest.generation();
            log.accept(String.format("could not load grid %s from %s: %s", Integer.toUnsignedString(manifest.generation()),
                    manifest.path(), e));
        }
    }
}
