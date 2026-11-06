package siyollvm;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Without clang: every example is inside the subset and has an expected output. */
class ExamplesCompileTest {
    @TestFactory
    Stream<DynamicTest> everyExampleCompilesToIr() throws Exception {
        Path examples = TestPaths.projectDir().resolve("examples");
        assertTrue(Files.isDirectory(examples), "missing " + examples);
        return Files.list(examples).filter(p -> p.toString().endsWith(".siyo")).sorted()
                .map(source -> DynamicTest.dynamicTest(source.getFileName().toString(), () -> {
                    Compiler.IrResult result = Compiler.toIr(source.getFileName().toString(), Files.readString(source));
                    assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
                    assertTrue(Files.isRegularFile(source.resolveSibling(
                            source.getFileName().toString().replace(".siyo", ".expected"))));
                }));
    }
}
