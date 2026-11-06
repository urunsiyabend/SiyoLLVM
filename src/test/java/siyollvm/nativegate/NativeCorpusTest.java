package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;

import java.nio.file.Path;
import java.util.stream.Stream;

/** Semantics corpus: every program in src/test/resources/native, built with clang and run. */
@Tag("native")
class NativeCorpusTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    @TestFactory
    Stream<DynamicTest> corpus() throws Exception {
        Path directory = TestPaths.projectDir().resolve("src/test/resources/native");
        return NativeRunner.programsIn(directory).stream().map(source -> DynamicTest.dynamicTest(
                source.getFileName().toString(), () -> NativeRunner.buildRunAndCompare(clang, source)));
    }
}
