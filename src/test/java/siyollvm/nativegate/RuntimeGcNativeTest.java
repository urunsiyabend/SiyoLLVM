package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import siyollvm.Compiler;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;
import siyollvm.toolchain.RuntimeSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collector (see docs/superpowers/specs/2026-11-25-memory-reclamation-design.md).
 *
 * <p>{@link #collectorHooks} links {@code src/test/resources/gc/gc_hooks_test.c} with the runtime and
 * checks, through the runtime's {@code siyo_rt_gc_*} test hooks: interior-pointer lookup (small and
 * large blocks, string data inside a {@code SiyoBuf}, item pointers), zeroing, reclamation of
 * unreachable blocks, {@code SCAN} versus {@code NOSCAN}, cycles kept by an alias and reclaimed
 * without one, large blocks, a million-deep list (explicit mark stack), a block held only in a
 * callee-saved register, and the pending exception's thread-local payload and message.
 *
 * <p>The Siyo programs run under {@code SIYO_GC_STRESS} (a collection every n allocations) and must
 * print what the test computes in Java, identically with and without stress; the stats line must show
 * that collections happened.
 */
@Tag("native")
class RuntimeGcNativeTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static Path work(String name) throws Exception {
        return Files.createDirectories(TestPaths.tempRoot().resolve("runtime-gc").resolve(name));
    }

    @Test
    void collectorHooks() throws Exception {
        Path work = work("hooks");
        Path runtime = RuntimeSource.writeTo(work);
        Path test = work.resolve("gc_hooks_test.c");
        Files.copy(TestPaths.projectDir().resolve("src/test/resources/gc/gc_hooks_test.c"), test,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path exe = work.resolve("gc_hooks_test");
        List<String> command = new ArrayList<>(List.of(clang.executable().toString(), "-O1", "-std=c11", "-Wall",
                "-Wextra", "-Werror", "-I", work.toString(), test.toString(), runtime.toString(), "-pthread", "-o",
                exe.toString()));
        NativeRunner.Run build = NativeRunner.run(command, work, Map.of());
        assertEquals("", build.stderr(), "build");
        assertEquals(0, build.exitCode());

        NativeRunner.Run run = NativeRunner.run(List.of(exe.toString()), work, Map.of("SIYO_RT_STATS", "1"));
        String stdout = new String(run.stdout(), StandardCharsets.UTF_8);
        assertFalse(stdout.contains("FAIL"), stdout);
        assertTrue(stdout.endsWith("done 0\n"), stdout);
        assertEquals(0, run.exitCode(), stdout + run.stderr());
        Matcher stats = RuntimeStatsNativeTest.STATS.matcher(run.stderr().strip());
        assertTrue(stats.matches(), run.stderr());
        assertTrue(Long.parseLong(stats.group(1)) >= 10, "collections: " + run.stderr());
        assertTrue(stdout.lines().filter(l -> l.startsWith("ok ")).count() >= 40, stdout);
    }

    private static Path build(String name, String source) throws Exception {
        Path work = work(name);
        Compiler.IrResult ir = Compiler.toIr(name + ".siyo", source);
        assertTrue(ir.diagnostics().isEmpty(), ir.diagnostics().toString());
        Path ll = work.resolve(name + ".ll");
        Files.writeString(ll, ir.ir(), StandardCharsets.UTF_8);
        Path exe = work.resolve(name);
        clang.compile(ll, exe);
        return exe;
    }

    /** Runs exe plainly and under SIYO_GC_STRESS=stress; both print expected, the stressed run collects. */
    private static void runStressed(Path exe, int stress, String expected) throws Exception {
        NativeRunner.Run plain = NativeRunner.run(List.of(exe.toString()), exe.getParent(), Map.of("LC_ALL", "C"));
        assertEquals(expected, new String(plain.stdout(), StandardCharsets.UTF_8), "without stress");
        assertEquals("", plain.stderr());
        assertEquals(0, plain.exitCode());

        NativeRunner.Run stressed = NativeRunner.run(List.of(exe.toString()), exe.getParent(),
                Map.of("LC_ALL", "C", "SIYO_GC_STRESS", Integer.toString(stress), "SIYO_RT_STATS", "1"));
        assertEquals(expected, new String(stressed.stdout(), StandardCharsets.UTF_8), "SIYO_GC_STRESS=" + stress);
        assertEquals(0, stressed.exitCode(), stressed.stderr());
        Matcher stats = RuntimeStatsNativeTest.STATS.matcher(stressed.stderr().strip());
        assertTrue(stats.matches(), stressed.stderr());
        assertTrue(Long.parseLong(stats.group(1)) >= 10, "collections under stress: " + stressed.stderr());
    }

    private static int digits(long i) {
        return Long.toString(i).length();
    }

    @Test
    void structCyclesAndSelfContainingArrays() throws Exception {
        Path exe = build("gc_cycles_alias", """
                struct Node { id: int, label: string, next: any, items: any[] }

                fn idOf(n: Node) -> int {
                    return n.id
                }

                fn tagLength(a: Node) -> int {
                    imut ring: any[] = a.items
                    imut again: any[] = ring[0]
                    imut tag: string = again[1]
                    return len(tag)
                }

                fn walk(a: Node) -> int {
                    imut back: Node = a.next
                    return idOf(back) + a.id + tagLength(a) + len(a.label)
                }

                fn visit(b: Node) -> int {
                    imut a: Node = b.next
                    return walk(a)
                }

                fn main() {
                    mut keep: any[] = []
                    mut i = 0
                    while i < 3000 {
                        imut a = Node { id: i + 1000, label: "a" + toString(i) }
                        imut b = Node { id: i + 2000, label: "b" + toString(i), next: a }
                        a.next = b
                        mut ring: any[] = []
                        push(ring, ring)
                        push(ring, "r" + toString(i))
                        a.items = ring
                        if i % 100 == 0 {
                            push(keep, b)
                        }
                        i += 1
                    }
                    mut total = 0
                    for k in keep {
                        total += visit(k)
                    }
                    println(len(keep))
                    println(total)
                }
                """);
        long total = 0;
        for (int i = 0; i < 3000; i += 100) total += (i + 2000) + (i + 1000) + 2L * (1 + digits(i));
        runStressed(exe, 1, "30\n" + total + "\n");
    }

    @Test
    void mapsOfStructsReferringBack() throws Exception {
        Path exe = build("gc_map_back", """
                struct Acc { name: string, owner: any, hits: int }

                fn score(a: Acc) -> int {
                    return a.hits + len(a.name)
                }

                fn ownedBy(a: Acc, m: Map<string, any>) -> bool {
                    imut owner: any = a.owner
                    return owner == m
                }

                fn describe(s: Acc) -> string {
                    imut om: Map<string, any> = s.owner
                    return s.name + " " + toString(s.hits) + " " + toString(om.size())
                }

                fn build(n: int) -> Map<string, any> {
                    mut m: Map<string, any> = {}
                    mut i = 0
                    while i < n {
                        imut key = "k" + toString(i)
                        m[key] = Acc { name: key, owner: m, hits: i * 3 }
                        i += 1
                    }
                    mut j = 0
                    while j < n {
                        if j % 3 == 0 {
                            m.remove("k" + toString(j))
                        }
                        j += 1
                    }
                    return m
                }

                fn main() {
                    mut round = 0
                    mut total = 0L
                    mut survivor: any = null
                    while round < 4 {
                        imut m = build(2000)
                        mut sum = 0L
                        for k in m {
                            imut a: Acc = m[k]
                            if ownedBy(a, m) {
                                sum += score(a)
                            }
                        }
                        total += sum
                        if round == 2 {
                            survivor = m["k1"]
                        }
                        round += 1
                    }
                    println(total)
                    println(describe(survivor))
                }
                """);
        long sum = 0;
        for (int j = 0; j < 2000; j++) if (j % 3 != 0) sum += 3L * j + 1 + digits(j);
        runStressed(exe, 13, (4 * sum) + "\nk1 3 1333\n");
    }

    @Test
    void largeArraysAndGrowth() throws Exception {
        Path exe = build("gc_large_array", """
                fn main() {
                    mut xs: int[] = []
                    mut i = 0
                    while i < 1000000 {
                        push(xs, i % 100)
                        i += 1
                    }
                    mut sum = 0L
                    for x in xs {
                        sum += x
                    }
                    println(len(xs))
                    println(sum)
                }
                """);
        runStressed(exe, 1, "1000000\n49500000\n");

        Path boxed = build("gc_boxed_array", """
                fn main() {
                    mut xs: int[] = []
                    mut ys: string[] = []
                    mut i = 0
                    while i < 200000 {
                        push(xs, i + 1000)
                        if i % 4 == 0 {
                            push(ys, toString(i))
                        }
                        i += 1
                    }
                    mut sum = 0L
                    for x in xs {
                        sum += x
                    }
                    mut chars = 0
                    for y in ys {
                        chars += len(y)
                    }
                    println(len(xs))
                    println(len(ys))
                    println(sum)
                    println(chars)
                }
                """);
        long expectedSum = 0;
        long chars = 0;
        for (int i = 0; i < 200000; i++) {
            expectedSum += i + 1000;
            if (i % 4 == 0) chars += digits(i);
        }
        runStressed(boxed, 1009, "200000\n50000\n" + expectedSum + "\n" + chars + "\n");
    }

    @Test
    void mapPastManyRehashes() throws Exception {
        Path exe = build("gc_map_rehash", """
                fn main() {
                    mut m: Map<int, int> = {}
                    mut i = 0
                    while i < 50000 {
                        m[i * 7 + 500] = i + 100000
                        if i % 5 == 0 {
                            m.remove((i / 2) * 7 + 500)
                        }
                        i += 1
                    }
                    mut sum = 0L
                    for k in m {
                        imut v: int = m[k]
                        sum += v + k
                    }
                    println(m.size())
                    println(sum)
                }
                """);
        java.util.LinkedHashMap<Integer, Integer> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 50000; i++) {
            m.put(i * 7 + 500, i + 100000);
            if (i % 5 == 0) m.remove((i / 2) * 7 + 500);
        }
        long sum = 0;
        for (var e : m.entrySet()) sum += e.getKey() + e.getValue();
        runStressed(exe, 101, m.size() + "\n" + sum + "\n");
    }

    @Test
    void stringBuffersBeyondTwoKiB() throws Exception {
        Path exe = build("gc_long_string", """
                fn main() {
                    mut s = ""
                    mut i = 0
                    while i < 5000 {
                        s += "ab" + toString(i % 10)
                        imut scratch = s + "!"
                        if len(scratch) != len(s) + 1 {
                            println("bad")
                        }
                        i += 1
                    }
                    println(len(s))
                    println(s[14999])
                    println(indexOf(s, "ab9ab0"))
                    println(substring(s, 7000, 7009))
                    mut parts = split(s, "ab")
                    println(len(parts))
                }
                """);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 5000; i++) s.append("ab").append(i % 10);
        String text = s.toString();
        String expected = text.length() + "\n" + text.charAt(14999) + "\n" + text.indexOf("ab9ab0") + "\n"
                + text.substring(7000, 7009) + "\n" + text.split(java.util.regex.Pattern.quote("ab"), -1).length + "\n";
        runStressed(exe, 1, expected);
    }

    @Test
    void exceptionsInLoops() throws Exception {
        Path exe = build("gc_exception_loop", """
                fn risky(i: int) -> int {
                    if i % 2 == 0 {
                        throw "e" + toString(i)
                    }
                    if i % 3 == 0 {
                        imut xs: int[] = []
                        return xs[i]
                    }
                    return i
                }

                fn main() {
                    mut caught = 0
                    mut lens = 0L
                    mut sum = 0L
                    mut i = 0
                    while i < 5000 {
                        try {
                            sum += risky(i)
                        } catch e: string {
                            caught += 1
                            lens += len(e)
                        }
                        i += 1
                    }
                    println(caught)
                    println(lens)
                    println(sum)
                }
                """);
        long caught = 0, lens = 0, sum = 0;
        for (int i = 0; i < 5000; i++) {
            if (i % 2 == 0) {
                caught++;
                lens += 1 + digits(i);
            } else if (i % 3 == 0) {
                caught++;
                lens += ("Index " + i + " out of bounds for length 0").length();
            } else {
                sum += i;
            }
        }
        runStressed(exe, 1, caught + "\n" + lens + "\n" + sum + "\n");
    }
}
