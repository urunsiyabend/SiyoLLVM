package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import siyollvm.Compiler;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A null map used as a receiver. The JVM's message is a "helpful"
 * NullPointerException naming the JVM's own classes and locals (for example
 * {@code Cannot invoke "codeanalysis.SiyoMap.get(Object)" because "<local37>"
 * is null}), so these are not corpus cases: SiyoLLVM prints the class alone
 * (docs/SUBSET.md, "Deviations"). len of a null map is 0 on both.
 */
@Tag("native")
class MapNullNativeTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static NativeRunner.Run buildAndRun(String name, String source) throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("map-null"));
        Compiler.IrResult ir = Compiler.toIr(name + ".siyo", source);
        assertTrue(ir.diagnostics().isEmpty(), ir.diagnostics().toString());
        Path ll = work.resolve(name + ".ll");
        Files.writeString(ll, ir.ir(), StandardCharsets.UTF_8);
        Path exe = work.resolve(name);
        clang.compile(ll, exe);
        return NativeRunner.run(List.of(exe.toString()), work, Map.of("LC_ALL", "C"));
    }

    private static String withNull(String statement) {
        return "fn main() {\n    mut none: Map<string, int> = null\n    println(\"start \" + toString(len(none)))\n    "
                + statement + "\n    println(\"unreachable\")\n}\n";
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "println(none[\"a\"])",
            "none[\"a\"] = 1",
            "none[\"a\"] += 1",
            "none.set(\"a\", 1)",
            "println(none.get(\"a\"))",
            "println(none.has(\"a\"))",
            "none.remove(\"a\")",
            "println(none.size())",
            "println(none.keys())",
            "for k in none { println(k) }",
    })
    void nullMapRaisesNullPointerException(String statement) throws Exception {
        String name = "npe_" + Integer.toHexString(statement.hashCode());
        NativeRunner.Run run = buildAndRun(name, withNull(statement));
        assertEquals("start 0\n", new String(run.stdout(), StandardCharsets.UTF_8));
        assertEquals("java.lang.NullPointerException\n", run.stderr());
        assertEquals(1, run.exitCode());
    }

    @Test
    void nullMapIsCatchable() throws Exception {
        NativeRunner.Run run = buildAndRun("catch", "fn main() {\n    mut none: Map<string, int> = null\n"
                + "    println(\"start \" + toString(len(none)))\n"
                + "    try { none[\"k\"] = 1 } catch e { println(e) }\n    println(\"done\")\n}\n");
        assertEquals("start 0\nNullPointerException\ndone\n", new String(run.stdout(), StandardCharsets.UTF_8), run.stderr());
        assertEquals(0, run.exitCode());
    }
}
