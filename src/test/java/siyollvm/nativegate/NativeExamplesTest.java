package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;

import java.util.stream.Stream;

/** Every program in examples/ builds, runs, and prints its .expected output. */
@Tag("native")
class NativeExamplesTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    @TestFactory
    Stream<DynamicTest> examples() throws Exception {
        return NativeRunner.programsIn(TestPaths.projectDir().resolve("examples")).stream()
                .map(source -> DynamicTest.dynamicTest(source.getFileName().toString(),
                        () -> NativeRunner.buildRunAndCompare(clang, source)));
    }
}
