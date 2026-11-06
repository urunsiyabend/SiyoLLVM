package siyollvm.toolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The C runtime ({@code siyo_rt.c} and {@code siyo_rt.h}) shipped in the jar.
 * clang compiles it beside the program IR, which declares its functions and
 * defines {@code siyo_program}; the runtime defines {@code main}.
 */
public final class RuntimeSource {
    public static final String C_FILE = "siyo_rt.c";
    public static final String HEADER_FILE = "siyo_rt.h";
    private static final String RESOURCE_DIRECTORY = "/siyollvm/runtime/";

    private RuntimeSource() {}

    /**
     * Writes both runtime files into {@code directory}, creating it if needed
     * and replacing earlier copies.
     *
     * @return The path of {@code siyo_rt.c}.
     */
    public static Path writeTo(Path directory) throws IOException {
        Files.createDirectories(directory);
        copy(HEADER_FILE, directory);
        return copy(C_FILE, directory);
    }

    private static Path copy(String name, Path directory) throws IOException {
        Path target = directory.resolve(name);
        try (InputStream in = RuntimeSource.class.getResourceAsStream(RESOURCE_DIRECTORY + name)) {
            if (in == null) throw new IOException("runtime resource " + RESOURCE_DIRECTORY + name + " is missing from the jar");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
