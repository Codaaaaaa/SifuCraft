package dev.sifucraft.client;

import dev.sifucraft.collision.MapPlacement;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Each Sifu map's place in the Minecraft world, assigned in order of first visit and kept with the
 * save (sifucraft-maps.properties), so a map always comes back to the same place, with whatever was
 * built there. Maps sit {@link #SPACING} blocks apart along x; Unreal z = 0 is y = {@link #ORIGIN_Y},
 * which keeps Sifu's levels (about -70 m .. +40 m) well above the dev world's flat ground at y = -60.
 */
final class MapSlots {
    static final int SPACING = 4096;
    static final int ORIGIN_Y = 64;
    private static final String FILE_NAME = "sifucraft-maps.properties";

    private final Consumer<String> log;
    private final Properties slots = new Properties();
    private Path file;

    MapSlots(Consumer<String> log) {
        this.log = log;
    }

    /** Uses the save at {@code worldRoot}; no-op if it is already the one in use. */
    void open(Path worldRoot) {
        Path wanted = worldRoot.resolve(FILE_NAME);
        if (wanted.equals(file)) return;
        file = wanted;
        slots.clear();
        if (!Files.exists(file)) return;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            slots.load(in);
        } catch (IOException e) {
            log.accept("could not read " + file + ": " + e);
        }
    }

    void close() {
        file = null;
        slots.clear();
    }

    boolean isOpen() {
        return file != null;
    }

    MapPlacement placement(String map) {
        String slot = slots.getProperty(map);
        if (slot == null) {
            slot = Integer.toString(slots.size());
            slots.setProperty(map, slot);
            save();
            log.accept("map " + map + " placed in slot " + slot + " (x " + Integer.parseInt(slot) * SPACING + ")");
        }
        return new MapPlacement(map, Integer.parseInt(slot) * SPACING, ORIGIN_Y, 0);
    }

    private void save() {
        if (file == null) return;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            slots.store(out, "SifuCraft: Sifu map -> place in this world (x = slot * " + SPACING + ")");
        } catch (IOException e) {
            log.accept("could not write " + file + ": " + e);
        }
    }
}
