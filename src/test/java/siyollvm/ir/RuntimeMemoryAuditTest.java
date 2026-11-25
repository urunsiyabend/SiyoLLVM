package siyollvm.ir;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The collector scans its own SCAN blocks, the program thread's stack and registers, the
 * executable's data and bss, and the pending exception; nothing else. So memory outside it
 * (malloc'd scratch) must never hold a heap pointer, and a NOSCAN block must never hold one either.
 * Each site was audited (see "memory" in siyo_rt.c); this test pins the audited sets, by function,
 * so that a new site fails here until it is audited and added.
 */
class RuntimeMemoryAuditTest {
    /** The collector's own bookkeeping (descriptors, page table, mark stack) and siyo_rt_scratch itself. */
    private static final Set<String> MALLOC = Set.of("siyo_gc_alloc_large", "siyo_gc_new_page", "siyo_gc_push",
            "siyo_gc_register", "siyo_gc_table_reserve", "siyo_rt_scratch");

    /** Scratch of UTF-16 units, raw bytes or C strings only (never a heap pointer). */
    private static final Set<String> SCRATCH = Set.of("siyo_rt_bytes_reserve", "siyo_rt_io_list_dir",
            "siyo_rt_str_replace", "siyo_rt_to_units");

    /** Releases scratch (or, in siyo_gc_*, the collector's bookkeeping); never a heap block. */
    private static final Set<String> FREE = Set.of("siyo_gc_release", "siyo_gc_table_reserve",
            "siyo_rt_bytes_reserve", "siyo_rt_decode_utf8", "siyo_rt_from_os", "siyo_rt_io_list_dir",
            "siyo_rt_io_read_file", "siyo_rt_os_cwd", "siyo_rt_os_env", "siyo_rt_path", "siyo_rt_read_text",
            "siyo_rt_str_index_of", "siyo_rt_str_replace", "siyo_rt_str_split", "siyo_rt_units_match",
            "siyo_rt_write");

    /** NOSCAN blocks: SiyoBuf bytes, box payloads, map slot indexes, a path's OS bytes. */
    private static final Set<String> NOSCAN = Set.of("siyo_rt_box", "siyo_rt_buf_new", "siyo_rt_map_grow",
            "siyo_rt_path");

    @Test
    void mallocFreeScratchAndNoscanSitesAreTheAuditedOnes() throws IOException {
        Map<String, String> bodies = RuntimeIrTest.functionBodies(runtimeSource());
        assertEquals(new TreeSet<>(MALLOC), callers(bodies, "\\b(?:malloc|calloc|realloc)\\s*\\("), "malloc");
        assertEquals(new TreeSet<>(SCRATCH), callers(bodies, "\\bsiyo_rt_scratch\\s*\\("), "siyo_rt_scratch");
        assertEquals(new TreeSet<>(FREE), callers(bodies, "\\bfree\\s*\\("), "free");
        assertEquals(new TreeSet<>(NOSCAN), callers(bodies, "\\bsiyo_rt_alloc_noscan\\s*\\("), "NOSCAN");
    }

    private static Set<String> callers(Map<String, String> bodies, String regex) {
        Pattern call = Pattern.compile(regex);
        Set<String> found = new TreeSet<>();
        bodies.forEach((name, body) -> {
            if (call.matcher(body).find()) found.add(name);
        });
        return found;
    }

    private static String runtimeSource() throws IOException {
        try (InputStream in = RuntimeMemoryAuditTest.class.getResourceAsStream("/siyollvm/runtime/siyo_rt.c")) {
            assertNotNull(in, "siyo_rt.c resource");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
