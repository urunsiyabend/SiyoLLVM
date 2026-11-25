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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SIYO_RT_STATS=1} makes a program write one line to stderr at exit,
 * {@code siyo-rt: collections=<c> heap_peak_kb=<h> rss_peak_kb=<r>}, as the
 * last stderr line and after stdout is flushed, on every way out: the end of
 * {@code main}, {@code os.exit}, an uncaught exception, a fatal runtime
 * error. Without the variable (or with another value) nothing changes.
 */
@Tag("native")
class RuntimeStatsNativeTest {
    /** The stats line, as {@code MemoryBoundsNativeTest} parses it. */
    static final Pattern STATS = Pattern.compile(
            "siyo-rt: collections=(\\d+) heap_peak_kb=(\\d+) rss_peak_kb=(\\d+)");

    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static Path build(String name, String source) throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("runtime-stats"));
        Compiler.IrResult ir = Compiler.toIr(name + ".siyo", source);
        assertTrue(ir.diagnostics().isEmpty(), ir.diagnostics().toString());
        Path ll = work.resolve(name + ".ll");
        Files.writeString(ll, ir.ir(), StandardCharsets.UTF_8);
        Path exe = work.resolve(name);
        clang.compile(ll, exe);
        return exe;
    }

    private static NativeRunner.Run run(Path exe, Map<String, String> env) throws Exception {
        return NativeRunner.run(List.of(exe.toString()), exe.getParent(), env);
    }

    /** Runs exe with and without SIYO_RT_STATS=1 and checks that only the last stderr line differs. */
    private static Matcher statsRun(Path exe, String stdout, String stderr, int exit) throws Exception {
        NativeRunner.Run plain = run(exe, Map.of());
        assertEquals(stdout, new String(plain.stdout(), StandardCharsets.UTF_8));
        assertEquals(stderr, plain.stderr());
        assertEquals(exit, plain.exitCode());
        for (String other : List.of("0", "", "yes")) {
            NativeRunner.Run off = run(exe, Map.of("SIYO_RT_STATS", other));
            assertEquals(stderr, off.stderr(), "SIYO_RT_STATS=" + other);
        }

        NativeRunner.Run stats = run(exe, Map.of("SIYO_RT_STATS", "1"));
        assertEquals(stdout, new String(stats.stdout(), StandardCharsets.UTF_8));
        assertEquals(exit, stats.exitCode());
        assertTrue(stats.stderr().startsWith(stderr), stats.stderr());
        String last = stats.stderr().substring(stderr.length());
        assertTrue(last.endsWith("\n"), last);
        Matcher matcher = STATS.matcher(last.substring(0, last.length() - 1));
        assertTrue(matcher.matches(), "stats line: " + last);
        assertTrue(Long.parseLong(matcher.group(3)) > 0, last);
        return matcher;
    }

    /** A program that allocates far below the first trigger (4 MiB) never collects. */
    private static void assertNoCollection(Matcher stats) {
        assertEquals("0", stats.group(1), "collections of a tiny program");
    }

    @Test
    void endOfMainCountsAllocatedBytes() throws Exception {
        // 200 strings of about 10 000 bytes, all kept live: a heap peak of at least 2000 KiB. Building
        // them allocates tens of MB in all (strings.repeat), so the collector runs, and frees the rest.
        Path exe = build("stats_main", """
                import "std/strings"
                fn main() {
                    mut keep: string[] = []
                    for i in range(0, 200) {
                        push(keep, strings.repeat("x", 10000) + toString(i))
                    }
                    println(len(keep))
                }
                """);
        Matcher stats = statsRun(exe, "200\n", "", 0);
        long heapKb = Long.parseLong(stats.group(2));
        assertTrue(heapKb >= 2000, "heap_peak_kb " + heapKb);
        assertTrue(Long.parseLong(stats.group(1)) >= 1, "collections " + stats.group(1));
    }

    @Test
    void stderrOutputComesFirst() throws Exception {
        Path exe = build("stats_eprintln", """
                import "std/io"
                fn main() {
                    println("out")
                    io.eprintln("err")
                }
                """);
        Matcher stats = statsRun(exe, "out\n", "err\n", 0);
        assertNoCollection(stats);
        // The program's own peak, not the JVM's: Linux carries ru_maxrss across fork and execve.
        long rssKb = Long.parseLong(stats.group(3));
        assertTrue(rssKb < 16 * 1024, "rss_peak_kb " + rssKb + " of a tiny program");
    }

    @Test
    void osExit() throws Exception {
        Path exe = build("stats_exit", """
                import "std/os"
                fn main() {
                    print("before")
                    os.exit(3)
                    println("unreachable")
                }
                """);
        assertNoCollection(statsRun(exe, "before", "", 3));
    }

    @Test
    void uncaughtException() throws Exception {
        Path exe = build("stats_uncaught", """
                fn main() {
                    println("a")
                    throw "boom"
                }
                """);
        assertNoCollection(statsRun(exe, "a\n", "codeanalysis.SiyoThrow: boom\n", 1));
    }

    @Test
    void stackOverflow() throws Exception {
        Path exe = build("stats_overflow", "fn f(n: int) { f(n + 1) }\nfn main() { print(\"x\")\n f(0) }\n");
        assertNoCollection(statsRun(exe, "x", "java.lang.StackOverflowError\n", 1));
    }
}
