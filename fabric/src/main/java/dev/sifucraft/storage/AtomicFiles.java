package dev.sifucraft.storage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Replace a bridge index only after its complete UTF-8 contents have reached disk. */
public final class AtomicFiles {
    @FunctionalInterface public interface TextWriter { void write(BufferedWriter out) throws IOException; }
    private AtomicFiles() {}

    public static void write(Path target, TextWriter content) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".", ".tmp");
        try {
            try (var out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { content.write(out); }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
