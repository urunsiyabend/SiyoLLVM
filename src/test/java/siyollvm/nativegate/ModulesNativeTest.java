package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Multi-file corpus: every directory in src/test/resources/modules is a
 * program whose {@code main.siyo} imports the other files, built with clang
 * and run, then compared with {@code main.expected[-stderr|-exit]}.
 */
@Tag("native")
class ModulesNativeTest {
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
        return cases.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(),
                () -> NativeRunner.buildRunAndCompare(clang, dir.resolve("main.siyo"), dir.getFileName().toString())));
    }
}
