package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import siyollvm.TestPaths;

import java.io.File;
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
 * Agreement between the independent Python oracle ({@code projects/ledger/oracle/ledger.py},
 * written only from {@code FORMAT.md}) and the committed expectations of every case in
 * {@code projects/ledger/tests/}, which the JVM reference produced.
 *
 * <p>Each case runs in a fresh directory holding a copy of its {@code files/}, under
 * {@code LC_ALL=C}, with the arguments from {@code args} (one per line). stdout, stderr,
 * the exit code and the report must match byte for byte; without {@code expected.report}
 * the report must not exist.
 *
 * <p>{@code python3} is mandatory: when it is not on {@code PATH} the test fails.
 */
@Tag("native")
class LedgerOracleNativeTest {
    private static final Path PROJECT = TestPaths.projectDir().resolve("projects/ledger");
    private static final Path ORACLE = PROJECT.resolve("oracle/ledger.py").toAbsolutePath();

    private static Path python;
    private static Path work;

    @BeforeAll
    static void locatePython() throws Exception {
        python = onPath("python3");
        assertNotNull(python, "python3 is required on PATH for the ledger oracle");
        assertTrue(Files.isRegularFile(ORACLE), "missing " + ORACLE);
        work = Files.createDirectories(TestPaths.tempRoot().resolve("native").resolve("ledger-oracle"));
    }

    private static Path onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(entry, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
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
        String expectedStderr = Files.readString(dir.resolve("expected.stderr"), StandardCharsets.UTF_8);
        int expectedExit = Integer.parseInt(Files.readString(dir.resolve("expected.exit")).strip());
        Path expectedReport = dir.resolve("expected.report");

        Path caseWork = Files.createDirectories(work.resolve(name));
        Path runDir = freshRunDirectory(caseWork, dir.resolve("files"));
        // Pass each line's bytes as they are, as LedgerAppNativeTest does.
        List<String> command = List.of("/bin/sh", "-c", "f=$1; py=$2; oracle=$3; set --; "
                + "while IFS= read -r l || [ -n \"$l\" ]; do set -- \"$@\" \"$l\"; done < \"$f\"; "
                + "exec \"$py\" \"$oracle\" \"$@\"", "sh", argsFile.toAbsolutePath().toString(),
                python.toString(), ORACLE.toString());
        NativeRunner.Run run = NativeRunner.run(command, runDir, caseWork, Map.of("LC_ALL", "C"));

        assertArrayEquals(expectedStdout, run.stdout(),
                name + " stdout:\n" + new String(run.stdout(), StandardCharsets.UTF_8));
        assertEquals(expectedStderr, run.stderr(), name + " stderr");
        assertEquals(expectedExit, run.exitCode(), name + " exit code; stderr: " + run.stderr());
        if (args.size() >= 2) {
            Path report = runDir.resolve(args.get(1));
            if (Files.isRegularFile(expectedReport)) {
                assertTrue(Files.isRegularFile(report), name + ": no report at " + report);
                assertArrayEquals(Files.readAllBytes(expectedReport), Files.readAllBytes(report),
                        name + " report:\n" + Files.readString(report, StandardCharsets.UTF_8));
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
