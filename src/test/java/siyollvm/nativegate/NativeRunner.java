package siyollvm.nativegate;

import siyollvm.Compiler;
import siyollvm.TestPaths;
import siyollvm.ir.IrStructure;
import siyollvm.toolchain.Clang;
import siyollvm.toolchain.ToolchainException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Builds a {@code .siyo} file to a native executable and checks what it does
 * against {@code .expected} (stdout bytes), {@code .expected-stderr} (default
 * empty) and {@code .expected-exit} (default 0) next to it.
 *
 * <p>clang is mandatory here: when it cannot be found the test fails, it is
 * never skipped.
 */
final class NativeRunner {
    private static final long RUN_TIMEOUT_SECONDS = 60;

    private NativeRunner() {}

    static Clang clang() throws ToolchainException {
        return Clang.locate(Optional.empty(), System.getenv());
    }

    static List<Path> programsIn(Path directory) throws IOException {
        assertTrue(Files.isDirectory(directory), "missing " + directory);
        List<Path> programs = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.toString().endsWith(".siyo")).sorted().forEach(programs::add);
        }
        assertTrue(!programs.isEmpty(), "no programs in " + directory);
        return programs;
    }

    /**
     * Builds and runs {@code source} under {@code LC_ALL=C}, or the locale named in
     * {@code <name>.locale} beside it, then compares with its expectation files.
     * The program runs in a fresh directory holding a copy of {@code files/} beside the source, if
     * any, with the lines of {@code <name>.args} beside it, if any, as its arguments.
     */
    static void buildRunAndCompare(Clang clang, Path source) throws Exception {
        buildRunAndCompare(clang, source, source.getFileName().toString().replace(".siyo", ""));
    }

    /**
     * As above, naming the work directory {@code name}: a multi-file case is a
     * directory whose {@code main.siyo} imports the rest, so it is named after
     * the directory. Imports resolve beside the source.
     */
    static void buildRunAndCompare(Clang clang, Path source, String name) throws Exception {
        Path expectedOut = sibling(source, ".expected");
        assertTrue(Files.isRegularFile(expectedOut), "missing " + expectedOut);
        byte[] expectedStdout = Files.readAllBytes(expectedOut);
        Path stderrFile = sibling(source, ".expected-stderr");
        String expectedStderr = Files.isRegularFile(stderrFile) ? Files.readString(stderrFile) : "";
        Path exitFile = sibling(source, ".expected-exit");
        int expectedExit = Files.isRegularFile(exitFile) ? Integer.parseInt(Files.readString(exitFile).strip()) : 0;

        Compiler.IrResult ir = Compiler.toIr(source.getFileName().toString(), source.toAbsolutePath(),
                Files.readString(source));
        assertTrue(ir.diagnostics().isEmpty(), name + ": " + ir.diagnostics());
        assertNotNull(ir.ir());
        IrStructure.assertWellFormed(ir.ir());

        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("native").resolve(name));
        Path ll = work.resolve(name + ".ll");
        Path exe = work.resolve(name);
        Files.writeString(ll, ir.ir(), StandardCharsets.UTF_8);
        clang.compile(ll, exe);

        Path runDir = freshRunDirectory(work, source.resolveSibling("files"));
        Path argsFile = sibling(source, ".args");
        List<String> command = Files.isRegularFile(argsFile)
                // The shell passes each line's bytes as they are, as the oracle's does.
                ? List.of("/bin/sh", "-c", "f=$1; exe=$2; set --; "
                        + "while IFS= read -r l || [ -n \"$l\" ]; do set -- \"$@\" \"$l\"; done < \"$f\"; "
                        + "exec \"$exe\" \"$@\"", "sh", argsFile.toAbsolutePath().toString(), exe.toString())
                : List.of(exe.toString());
        Path localeFile = sibling(source, ".locale");
        String locale = Files.isRegularFile(localeFile) ? Files.readString(localeFile).strip() : "C";
        Run run = run(command, runDir, work, Map.of("LC_ALL", locale));
        assertArrayEquals(expectedStdout, run.stdout(),
                name + " stdout:\n" + new String(run.stdout(), StandardCharsets.UTF_8));
        assertEquals(expectedStderr, run.stderr(), name + " stderr");
        assertEquals(expectedExit, run.exitCode(), name + " exit code; stderr: " + run.stderr());
    }

    record Run(int exitCode, byte[] stdout, String stderr) {}

    /**
     * An empty {@code run} directory in {@code work}, holding a copy of the
     * case's {@code files/} fixtures when it has them: the program runs there,
     * so what it writes never reaches the source tree, and each run starts
     * afresh. {@code rm} and {@code cp} handle the names byte for byte,
     * whatever the JVM's file name encoding.
     */
    private static Path freshRunDirectory(Path work, Path fixtures) throws Exception {
        Path runDir = work.resolve("run");
        Run removed = run(List.of("rm", "-rf", "--", runDir.toString()), work, work, Map.of());
        assertEquals(0, removed.exitCode(), removed.stderr());
        Files.createDirectories(runDir);
        if (Files.isDirectory(fixtures)) {
            Run copied = run(List.of("cp", "-R", "--", fixtures + "/.", runDir.toString()), work, work, Map.of());
            assertEquals(0, copied.exitCode(), copied.stderr());
        }
        return runDir;
    }

    /** Runs a command with a clean locale ({@code LANG}/{@code LC_*} removed, then {@code extraEnv} added). */
    static Run run(List<String> command, Path directory, Map<String, String> extraEnv) throws Exception {
        return run(command, directory, directory, extraEnv);
    }

    /** As above, keeping the captured output in {@code outputDirectory}. */
    static Run run(List<String> command, Path directory, Path outputDirectory, Map<String, String> extraEnv)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().keySet().removeIf(k -> k.equals("LANG") || k.startsWith("LC_"));
        builder.environment().putAll(extraEnv);
        Path out = Files.createTempFile(outputDirectory, "stdout", ".bin");
        Path err = Files.createTempFile(outputDirectory, "stderr", ".txt");
        builder.redirectOutput(out.toFile()).redirectError(err.toFile()).redirectInput(ProcessBuilder.Redirect.from(
                Path.of("/dev/null").toFile()));
        Process process = builder.start();
        if (!process.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail(command + " did not finish within " + RUN_TIMEOUT_SECONDS + " s");
        }
        return new Run(process.exitValue(), Files.readAllBytes(out), Files.readString(err, StandardCharsets.UTF_8));
    }

    private static Path sibling(Path source, String suffix) {
        return source.resolveSibling(source.getFileName().toString().replace(".siyo", suffix));
    }
}
