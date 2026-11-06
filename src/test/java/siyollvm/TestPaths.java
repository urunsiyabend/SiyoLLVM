package siyollvm;

import java.nio.file.Path;

/** Locations the build passes to tests as system properties. */
public final class TestPaths {
    private TestPaths() {}

    public static Path projectDir() {
        return Path.of(System.getProperty("siyollvm.projectDir", ".")).toAbsolutePath().normalize();
    }

    public static Path tempRoot() {
        return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
    }
}
