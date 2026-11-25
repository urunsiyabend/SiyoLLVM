package siyollvm.nativegate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Every native corpus case ({@code src/test/resources/native}), every module case
 * ({@code src/test/resources/modules}, args, locale and files included) and every ledger case
 * ({@code projects/ledger/tests}) rebuilt and rerun with {@code SIYO_GC_STRESS} set, so that the
 * collector runs at (nearly) every allocation: a heap pointer held anywhere the collector does not
 * scan shows up as a wrong output or a crash. stdout, stderr, the exit status and the ledger's report
 * must be byte-identical to the existing expectations.
 *
 * <p>{@code SIYO_RT_STATS=1} is set too; its line is split off stderr before the comparison and its
 * collection count is checked: a case that allocated anything collected at least once.
 *
 * <p>Stress is {@code 1} (a collection before every allocation) for every case except the few in
 * {@link #SLOW_CASES}, where a collection per allocation is quadratic; each of those gets the
 * smallest n measured (2026-11-25, bounded, this host) to finish in a few seconds, still hundreds or
 * thousands of collections:
 * <ul>
 * <li>{@code range_large} keeps 10^5 boxed ints live across 2 x 10^5 allocations: 1 s at n = 97
 * (2059 collections), 3 s at n = 31; n = 1 exceeds the 60 s run limit.</li>
 * <li>{@code array_self_print} recurses until the 16 MiB stack overflows, about 2.4 million
 * allocations, and every collection scans the whole (deep) stack: 3.6 s at n = 4001 (601
 * collections), 15 s at n = 1009.</li>
 * <li>the ledger's {@code large} case (321 KB of input, 573 413 allocations): 50 s at n = 1 (outputs
 * identical), 7.4 s at n = 7 (81 916 collections).</li>
 * </ul>
 */
@Tag("native")
class GcStressNativeTest {
    private static final String STRESS = "1";
    /** Cases too slow for a collection at every allocation, with their n (see the class comment). */
    private static final Map<String, String> SLOW_CASES = Map.of(
            "range_large", "97",
            "array_self_print", "4001",
            "ledger/large", "7");
    /** As ModulesNativeTest: cases whose expectations hold only for a user other than root. */
    private static final Set<String> UNPRIVILEGED = Set.of("stdio_readonly");

    private static Clang clang;
    private static final AtomicLong totalCollections = new AtomicLong();

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    @AfterAll
    static void collectorRan() {
        assertTrue(totalCollections.get() > 1000, "collections over the whole stress run: " + totalCollections);
    }

    private static String stressFor(String name) {
        return SLOW_CASES.getOrDefault(name, STRESS);
    }

    private static Map<String, String> env(String stress) {
        return Map.of("SIYO_GC_STRESS", stress, "SIYO_RT_STATS", "1");
    }

    private static void checkCollected(NativeRunner.Stats stats, String name) {
        totalCollections.addAndGet(stats.collections());
        // Every program allocates at least its os.args() array or a string; at n = 1 that is a collection.
        assertTrue(stats.collections() >= 1 || stats.heapPeakKb() == 0, name + ": no collection under stress");
    }

    @TestFactory
    Stream<DynamicTest> corpus() throws Exception {
        Path directory = TestPaths.projectDir().resolve("src/test/resources/native");
        return NativeRunner.programsIn(directory).stream().map(source -> {
            String name = source.getFileName().toString().replace(".siyo", "");
            return DynamicTest.dynamicTest(name, () -> checkCollected(
                    NativeRunner.buildRunAndCompare(clang, source, name, "gc-stress", env(stressFor(name))), name));
        });
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
                assumeFalse(runningAsRoot(), name + " expects permission errors, which root does not get");
            }
            checkCollected(NativeRunner.buildRunAndCompare(clang, dir.resolve("main.siyo"), "module-" + name,
                    "gc-stress", env(stressFor(name))), name);
        }));
    }

    @TestFactory
    Stream<DynamicTest> ledger() throws Exception {
        Path work = Files.createTempDirectory(Files.createDirectories(TestPaths.tempRoot().resolve("gc-stress")),
                "ledger-");
        Path executable = LedgerAppNativeTest.buildLedger(work);
        return LedgerAppNativeTest.caseDirectories().stream().map(dir -> DynamicTest.dynamicTest(
                dir.getFileName().toString(), () -> {
                    String name = dir.getFileName().toString();
                    checkCollected(LedgerAppNativeTest.runCase(executable, work, dir, env(stressFor("ledger/" + name))),
                            name);
                }));
    }

    private static boolean runningAsRoot() throws Exception {
        Path dir = Files.createDirectories(TestPaths.tempRoot().resolve("gc-stress"));
        NativeRunner.Run id = NativeRunner.run(List.of("id", "-u"), dir, Files.createTempDirectory(dir, "id"), Map.of());
        return new String(id.stdout(), java.nio.charset.StandardCharsets.UTF_8).strip().equals("0");
    }
}
