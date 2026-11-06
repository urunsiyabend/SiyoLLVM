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
    void createsTheDirectoryAndOverwritesOldCopies() throws Exception {
        Path target = dir.resolve("a/b");
        RuntimeSource.writeTo(target);
        Files.writeString(target.resolve("siyo_rt.h"), "stale");
        RuntimeSource.writeTo(target);
        assertTrue(Files.readString(target.resolve("siyo_rt.h")).contains("SiyoStr"));
    }
}
