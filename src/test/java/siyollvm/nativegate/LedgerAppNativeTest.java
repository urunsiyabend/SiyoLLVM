package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import siyollvm.Compiler;
import siyollvm.TestPaths;
import siyollvm.ir.IrStructure;
import siyollvm.toolchain.Clang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The F5 application, {@code projects/ledger}: builds {@code src/main.siyo}
 * once to a native executable (the path {@code bin/siyollvm build} takes:
 * {@link Compiler#toIr}, then clang), then runs every case in
 * {@code tests/<case>/} and compares it byte for byte with the expectations the
 * JVM reference produced ({@code tools/oracle-jvm.sh}).
 *
 * <p>A case has {@code args} (one argument per line), optionally {@code files/}
 * (copied into a fresh run directory, where the program runs under
 * {@code LC_ALL=C}), and {@code expected.stdout}, {@code expected.stderr},
 * {@code expected.exit} and, when the run writes the report (the second
 * argument), {@code expected.report}. Without {@code expected.report} the
 * report file must not exist after the run.
 *
 * <p>clang is mandatory: without it the build fails, so every case fails.
 */
@Tag("native")
class LedgerAppNativeTest {
    private static final Path PROJECT = TestPaths.projectDir().resolve("projects/ledger");

    private static Path executable;
    private static Path work;

    @BeforeAll
    static void build() throws Exception {
        Clang clang = NativeRunner.clang();
        Path source = PROJECT.resolve("src/main.siyo");
        Compiler.IrResult ir = Compiler.toIr(source.getFileName().toString(), source.toAbsolutePath(),
                Files.readString(source));
        assertTrue(ir.diagnostics().isEmpty(), "ledger: " + ir.diagnostics());
        assertNotNull(ir.ir());
        IrStructure.assertWellFormed(ir.ir());
        // A directory of its own per run, so concurrent or repeated runs never share files.
        work = Files.createTempDirectory(Files.createDirectories(TestPaths.tempRoot().resolve("native")), "ledger-app-");
        Path ll = work.resolve("ledger.ll");
        Files.writeString(ll, ir.ir(), StandardCharsets.UTF_8);
        executable = work.resolve("ledger");
        clang.compile(ll, executable);
    }

    @TestFactory
    Stream<DynamicTest> cases() throws Exception {
        Path tests = PROJECT.resolve("tests");
        List<Path> cases;
        try (Stream<Path> dirs = Files.list(tests)) {
            cases = dirs.filter(Files::isDirectory).sorted().toList();
        }
        assertFalse(cases.isEmpty(), "no cases in " + tests);
        return cases.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> runCase(dir)));
    }

    private static void runCase(Path dir) throws Exception {
        String name = dir.getFileName().toString();
        Path argsFile = dir.resolve("args");
        List<String> args = Files.readAllLines(argsFile, StandardCharsets.UTF_8);
        byte[] expectedStdout = Files.readAllBytes(dir.resolve("expected.stdout"));
        byte[] expectedStderr = Files.readAllBytes(dir.resolve("expected.stderr"));
        int expectedExit = Integer.parseInt(Files.readString(dir.resolve("expected.exit")).strip());
        Path expectedReport = dir.resolve("expected.report");
        if (expectedExit == 2) {
            assertEquals(0, expectedStdout.length, name + ": an error case expects empty stdout");
            assertFalse(Files.exists(expectedReport), name + ": an error case expects no report");
        }

        Path caseWork = Files.createDirectories(work.resolve(name));
        Path runDir = freshRunDirectory(caseWork, dir.resolve("files"));
        // The shell passes each line's bytes as they are, as the oracle script does.
        List<String> command = List.of("/bin/sh", "-c", "f=$1; exe=$2; set --; "
                + "while IFS= read -r l || [ -n \"$l\" ]; do set -- \"$@\" \"$l\"; done < \"$f\"; "
                + "exec \"$exe\" \"$@\"", "sh", argsFile.toAbsolutePath().toString(), executable.toString());
        NativeRunner.Run run = NativeRunner.run(command, runDir, caseWork, Map.of("LC_ALL", "C"));

        assertArrayEquals(expectedStdout, run.stdout(),
                name + " stdout:\n" + new String(run.stdout(), StandardCharsets.UTF_8));
        assertArrayEquals(expectedStderr, run.stderrBytes(), name + " stderr:\n" + run.stderr());
        assertEquals(expectedExit, run.exitCode(), name + " exit code; stderr: " + run.stderr());
        if (args.size() >= 2) {
            Path report = runDir.resolve(args.get(1));
            if (Files.isRegularFile(expectedReport)) {
                assertTrue(Files.isRegularFile(report), name + ": no report at " + report);
                assertArrayEquals(Files.readAllBytes(expectedReport), Files.readAllBytes(report),
                        name + " report:\n" + Files.readString(report, StandardCharsets.UTF_8));
            } else if (Files.isDirectory(dir.resolve("files").resolve(args.get(1)))) {
                // The report path is a directory the case provides: it must still be one.
                assertTrue(Files.isDirectory(report), name + ": report directory replaced at " + report);
            } else {
                assertFalse(Files.exists(report), name + ": unexpected report at " + report);
            }
        } else {
            assertFalse(Files.exists(expectedReport), name + ": expected.report without a report argument");
        }
    }

    /** An empty run directory holding a copy of the case's {@code files/}, if any. */
    private static Path freshRunDirectory(Path caseWork, Path fixtures) throws Exception {
        Path runDir = caseWork.resolve("run");
        NativeRunner.Run removed = NativeRunner.run(List.of("rm", "-rf", "--", runDir.toString()), caseWork, Map.of());
        assertEquals(0, removed.exitCode(), removed.stderr());
        Files.createDirectories(runDir);
        if (Files.isDirectory(fixtures)) {
            NativeRunner.Run copied = NativeRunner.run(List.of("cp", "-R", "--", fixtures + "/.", runDir.toString()),
                    caseWork, Map.of());
            assertEquals(0, copied.exitCode(), copied.stderr());
        }
        return runDir;
    }
}
