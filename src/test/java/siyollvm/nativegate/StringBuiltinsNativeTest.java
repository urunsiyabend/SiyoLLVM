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
 * String builtins on a null string (a global read before its initialiser).
 * The JVM's message is a "helpful" NullPointerException naming the JVM's own
 * classes and variables, so these are not corpus cases: SiyoLLVM prints the
 * class alone (docs/SUBSET.md, "Deviations").
 */
@Tag("native")
class StringBuiltinsNativeTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static NativeRunner.Run buildAndRun(String name, String source) throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("string-builtins"));
        Compiler.IrResult ir = Compiler.toIr(name + ".siyo", source);
        assertTrue(ir.diagnostics().isEmpty(), ir.diagnostics().toString());
        Path ll = work.resolve(name + ".ll");
        Files.writeString(ll, ir.ir(), StandardCharsets.UTF_8);
        Path exe = work.resolve(name);
        clang.compile(ll, exe);
        return NativeRunner.run(List.of(exe.toString()), work, Map.of("LC_ALL", "C"));
    }

    private static String withNull(String statement) {
        return "mut early = read()\nmut later = \"x\"\nfn read() -> string { return later }\n"
                + "fn main() {\n    println(\"start\")\n    " + statement + "\n    println(\"unreachable\")\n}\n";
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "println(len(early))",
            "println(substring(early, 0, 0))",
            "println(contains(early, \"a\"))",
            "println(contains(\"a\", early))",
            "println(indexOf(early, \"a\"))",
            "println(indexOf(\"a\", early))",
            "println(startsWith(early, \"a\"))",
            "println(startsWith(\"a\", early))",
            "println(endsWith(early, \"a\"))",
            "println(endsWith(\"a\", early))",
            "println(replace(early, \"a\", \"b\"))",
            "println(replace(\"a\", early, \"b\"))",
            "println(replace(\"a\", \"a\", early))",
            "println(trim(early))",
            "println(toUpper(early))",
            "println(toLower(early))",
            "println(ord(early))",
            "println(early < \"a\")",
            "println(\"a\" >= early)",
    })
    void nullStringRaisesNullPointerException(String statement) throws Exception {
        String name = "npe_" + Integer.toHexString(statement.hashCode());
        NativeRunner.Run run = buildAndRun(name, withNull(statement));
        assertEquals("start\n", new String(run.stdout(), StandardCharsets.UTF_8));
        assertEquals("java.lang.NullPointerException\n", run.stderr());
        assertEquals(1, run.exitCode());
    }

    @Test
    void userFunctionNamedLikeABuiltinIsCalled() throws Exception {
        NativeRunner.Run run = buildAndRun("shadow",
                "fn trim(s: string) -> string { return \"[\" + s + \"]\" }\nfn main() { println(trim(\" a \")) }\n");
        assertEquals("[ a ]\n", new String(run.stdout(), StandardCharsets.UTF_8), run.stderr());
        assertEquals(0, run.exitCode());
    }
}
