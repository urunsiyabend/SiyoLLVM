package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Multi-file corpus: every directory in src/test/resources/modules is a
 * program whose {@code main.siyo} imports the other files, built with clang
 * and run, then compared with {@code main.expected[-stderr|-exit]}.
 *
 * <p>The cases in {@link #UNPRIVILEGED} expect permission errors that root
 * does not get; only those cases are skipped when the tests run as root.
 * Without clang every case fails, as everywhere in the native gate.
 */
@Tag("native")
class ModulesNativeTest {
    /** Cases whose expectations hold only for a user other than root. */
    private static final Set<String> UNPRIVILEGED = Set.of("stdio_readonly");

    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    @TestFactory
    Stream<DynamicTest> modules() throws Exception {
        Path directory = TestPaths.projectDir().resolve("src/test/resources/modules");
        List<Path> cases;
        try (Stream<Path> dirs = Files.list(directory)) {
            cases = dirs.filter(Files::isDirectory).sorted().toList();
        }
        assertFalse(cases.isEmpty(), "no cases in " + directory);
        return cases.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> {
            String name = dir.getFileName().toString();
            if (UNPRIVILEGED.contains(name)) {
                assumeFalse(runningAsRoot(dir), name + " expects permission errors, which root does not get");
            }
            NativeRunner.buildRunAndCompare(clang, dir.resolve("main.siyo"), name);
        }));
    }

    /** Whether the effective user is root, by {@code id -u}. */
    private static boolean runningAsRoot(Path directory) throws Exception {
        NativeRunner.Run id = NativeRunner.run(List.of("id", "-u"), directory,
                Files.createTempDirectory(TestPaths.tempRoot(), "id"), Map.of());
        assertEquals(0, id.exitCode(), id.stderr());
        return new String(id.stdout(), StandardCharsets.UTF_8).strip().equals("0");
    }
}
