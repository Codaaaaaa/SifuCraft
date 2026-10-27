package dev.sifucraft.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

/** Runs without Sifu or any window/input API. Verifies the latch also gates IPC producers. */
public final class LiveTestFocusTest {
    private static void require(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            require(!LiveTestFocus.configured() && LiveTestFocus.allowed(), "ordinary game remains unaffected");
            var directory = Files.createTempDirectory("sifucraft-focus-check-").toAbsolutePath();
            var executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            var child = new ProcessBuilder(executable.toString(), "-Dsifucraft.testFocusSession=" + directory,
                "-cp", System.getProperty("java.class.path"), LiveTestFocusTest.class.getName(), "blocked").inheritIO().start();
            require(child.waitFor() == 0, "configured focus interlock child");
            System.out.println("Live test focus PASS: default inactive; persistent interruption; no command creation, transport open or publication after block");
            return;
        }
        require(LiveTestFocus.configured() && !LiveTestFocus.allowed(), "missing session refuses test");
        var block = Path.of(System.getProperty("sifucraft.testFocusSession"), "blocked.txt");
        require(Files.exists(block), "interruption persisted for the supervising agent");
        Files.delete(block); // Even a deleted external marker must not rearm this JVM.
        require(!LiveTestFocus.allowed(), "in-process interruption survives marker deletion");
        var broker = new WorldMotionRequests((mc, nativeSession) -> { throw new AssertionError("must not open transport after focus loss"); });
        var scope = new WorldMotionRequests.Scope(1, 2, 3, 4, 5);
        broker.attach(scope);
        var result = broker.request(scope, id -> { throw new AssertionError("must not create a command after focus loss"); });
        try { result.join(); throw new AssertionError("request should be refused"); }
        catch (CompletionException error) {
            require(error.getCause() instanceof WorldMotionRequests.Lost lost && !lost.mayHaveExecuted(), "refusal is definitively unsent");
        }
        broker.pump(100);
        require(broker.pending() == 0 && broker.scope() == null, "queued transport scope is cleared");
        broker.close();
    }
}
