package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import siyollvm.Compiler;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stack guard must hold whatever the process was started with: a large
 * environment or a small {@code ulimit -s} must never turn into SIGSEGV.
 * (Regression from the final review: the guard measured from main's stack and
 * ignored the argv/environment area above it.)
 */
@Tag("native")
class StackGuardNativeTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static Path build(String name, String source) throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("stack-guard"));
        Compiler.IrResult ir = Compiler.toIr(name + ".siyo", source);
        assertTrue(ir.diagnostics().isEmpty(), ir.diagnostics().toString());
        Path ll = work.resolve(name + ".ll");
        Files.writeString(ll, ir.ir(), StandardCharsets.UTF_8);
        Path exe = work.resolve(name);
        clang.compile(ll, exe);
        return exe;
    }

    @Test
    void unboundedRecursionWithALargeEnvironmentReportsStackOverflow() throws Exception {
        Path exe = build("unbounded", "fn f(n: int) { f(n + 1) }\nfn main() { f(0) }\n");
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < 6; i++) env.put("SIYOLLVM_BIG_" + i, "a".repeat(100_000));
        NativeRunner.Run run = NativeRunner.run(List.of(exe.toString()), exe.getParent(), env);
        assertEquals("java.lang.StackOverflowError\n", run.stderr());
        assertEquals(1, run.exitCode());
    }

    @Test
    void boundedRecursionDoesNotDependOnUlimit() throws Exception {
        Path exe = build("bounded", "fn d(n: int) -> int { if n == 0 { return 0 } return 1 + d(n - 1) }\n"
                + "fn main() { println(d(5000)) }\n");
        for (String limit : List.of("64", "256", "unlimited")) {
            NativeRunner.Run run = NativeRunner.run(List.of("/bin/sh", "-c", "ulimit -s " + limit + " && exec \"$0\"",
                    exe.toString()), exe.getParent(), Map.of());
            assertEquals("5000\n", new String(run.stdout(), StandardCharsets.UTF_8), "ulimit -s " + limit + ": " + run.stderr());
            assertEquals(0, run.exitCode(), "ulimit -s " + limit);
        }
    }

    @Test
    void unboundedRecursionUnderSmallUlimitReportsStackOverflow() throws Exception {
        Path exe = build("unbounded_small", "fn f(n: int) -> int { return 1 + f(n + 1) }\nfn main() { println(f(0)) }\n");
        NativeRunner.Run run = NativeRunner.run(List.of("/bin/sh", "-c", "ulimit -s 64 && exec \"$0\"", exe.toString()),
                exe.getParent(), Map.of());
        assertEquals("java.lang.StackOverflowError\n", run.stderr());
        assertEquals(1, run.exitCode());
    }
}
