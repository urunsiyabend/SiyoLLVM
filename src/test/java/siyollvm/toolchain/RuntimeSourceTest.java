package siyollvm.toolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSourceTest {
    @TempDir
    Path dir;

    @Test
    void writesRuntimeFiles() throws Exception {
        Path c = RuntimeSource.writeTo(dir);
        assertEquals(dir.resolve("siyo_rt.c"), c);
        assertTrue(Files.isRegularFile(c));
        assertTrue(Files.isRegularFile(dir.resolve("siyo_rt.h")));
        String source = Files.readString(c);
        assertTrue(source.contains("int main("), source);
        assertTrue(source.contains("#include \"siyo_rt.h\""), source);
        assertTrue(Files.readString(dir.resolve("siyo_rt.h")).contains("typedef struct SiyoStr"));
    }

    @Test
    void statsLineIsOptInAndPrintedAtExit() throws Exception {
        String source = Files.readString(RuntimeSource.writeTo(dir));
        assertTrue(source.contains("getenv(\"SIYO_RT_STATS\")"), "reads SIYO_RT_STATS");
        assertTrue(source.contains("atexit(siyo_rt_print_stats)"), "printed on every exit path");
        assertTrue(source.contains("\"VmHWM:\""), "this process's own peak RSS, not one inherited across exec");
        assertTrue(source.contains("\"siyo-rt: collections=%llu heap_peak_kb=%llu rss_peak_kb=%ld\\n\""),
                "the documented line format");
        assertTrue(source.contains("getenv(\"SIYO_GC_STRESS\")"), "reads SIYO_GC_STRESS");
    }

    @Test
    void createsTheDirectoryAndOverwritesOldCopies() throws Exception {
        Path target = dir.resolve("a/b");
        RuntimeSource.writeTo(target);
        Files.writeString(target.resolve("siyo_rt.h"), "stale");
        RuntimeSource.writeTo(target);
        assertTrue(Files.readString(target.resolve("siyo_rt.h")).contains("SiyoStr"));
    }
}
