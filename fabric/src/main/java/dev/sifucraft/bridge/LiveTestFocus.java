package dev.sifucraft.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Opt-in live-test interlock. A focus loss interrupts the run until a new user-confirmed session. */
public final class LiveTestFocus {
    private LiveTestFocus() {}
    private static final String SESSION = System.getProperty("sifucraft.testFocusSession", "");
    private static volatile boolean blocked;
    private static volatile String reason = "";
    public static boolean configured() { return !SESSION.isBlank(); }
    public static String reason() { return reason; }

    public static synchronized boolean allowed() {
        if (!configured()) return true;
        if (blocked) return false;
        Path directory = Path.of(SESSION);
        try {
            var block = directory.resolve("blocked.txt");
            if (Files.exists(block)) return block(directory, "focus session already interrupted");
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(directory.resolve("session.properties"))) { properties.load(reader); }
            long pid = Long.parseLong(properties.getProperty("gamePid"));
            long start = Long.parseLong(properties.getProperty("gameStartMs"));
            if (!"1".equals(properties.getProperty("version")) ||
                !directory.getFileName().toString().equals(properties.getProperty("sessionId")) ||
                !Files.readString(directory.getParent().resolve("active.txt")).trim().equals(properties.getProperty("sessionId")))
                return block(directory, "focus session identity changed");
            var process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive() ||
                process.get().info().startInstant().orElseThrow().toEpochMilli() != start ||
                !Path.of(process.get().info().command().orElseThrow()).getFileName().toString().equalsIgnoreCase("Sifu-Win64-Shipping.exe"))
                return block(directory, "game process identity changed");
            if (Foreground.pid() != pid) return block(directory, "game lost foreground; explicit user confirmation required");
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(directory.resolve("heartbeat")).toMillis();
            if (!Files.exists(directory.resolve("watcher-ready")) || age < 0 || age > 1500)
                return block(directory, "focus watchdog unavailable");
            return true;
        } catch (Exception | LinkageError error) {
            return block(directory, "focus check unavailable: " + error);
        }
    }

    private static boolean block(Path directory, String why) {
        blocked = true;
        reason = why;
        try {
            Files.writeString(directory.resolve("blocked.txt"), java.time.Instant.now() + " " + why,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        } catch (java.io.IOException ignored) { /* The in-process latch remains closed. */ }
        return false;
    }

    private static final class Foreground {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
        private static final MethodHandle WINDOW = LINKER.downcallHandle(USER32.find("GetForegroundWindow").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS));
        private static final MethodHandle PID = LINKER.downcallHandle(USER32.find("GetWindowThreadProcessId").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        static long pid() {
            try (Arena arena = Arena.ofConfined()) {
                var value = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment window = (MemorySegment) WINDOW.invokeExact();
                int ignored = (int) PID.invokeExact(window, value);
                return Integer.toUnsignedLong(value.get(ValueLayout.JAVA_INT, 0));
            } catch (Throwable error) { throw new IllegalStateException("foreground process unavailable", error); }
        }
    }
}
