package siyollvm.cli;

import org.junit.jupiter.api.Test;
import siyollvm.TestPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** bin/siyollvm against the packaged JAR (runs in the integration-test phase). */
class LauncherIT {

    private static Path launcher() {
        return TestPaths.projectDir().resolve("bin/siyollvm");
    }

    @Test
    void launcherAcceptsNonAsciiFileNameUnderCLocale() throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("launcher"));
        Files.writeString(work.resolve("şehir.siyo"), "fn main() { println(\"İstanbul\") }\n", StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(List.of(launcher().toString(), "emit-llvm", "şehir.siyo"))
                .directory(work.toFile());
        builder.environment().keySet().removeIf(k -> k.equals("LANG") || k.startsWith("LC_"));
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Path out = Files.createTempFile(work, "out", ".txt");
        Path err = Files.createTempFile(work, "err", ".txt");
        Process process = builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(err));
        assertTrue(Files.readString(out).contains("define i32 @main()"));
    }

    @Test
    void versionThroughLauncherMatchesTheBuild() throws Exception {
        Process process = new ProcessBuilder(List.of(launcher().toString(), "--version")).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertTrue(output.matches("siyollvm \\d+\\.\\d+\\.\\d+.*\\n"), output);
    }
}
