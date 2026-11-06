package siyollvm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frontend snapshot must stay byte-for-byte what PROVENANCE.md says it is. */
class SnapshotManifestTest {
    private static final Path UPSTREAM = TestPaths.projectDir().resolve("upstream/siyocompiler");
    private static final Path SOURCES = UPSTREAM.resolve("src/main/java");

    @Test
    void everyManifestEntryMatchesItsChecksum() throws Exception {
        List<String> lines = Files.readAllLines(UPSTREAM.resolve("MANIFEST.sha256"));
        assertFalse(lines.isEmpty(), "manifest is empty");
        for (String line : lines) {
            String[] parts = line.split(" {2}", 2);
            Path file = UPSTREAM.resolve(parts[1]);
            assertTrue(Files.isRegularFile(file), "missing snapshot file " + parts[1]);
            assertEquals(parts[0], sha256(file), "snapshot file was modified: " + parts[1]);
        }
    }

    @Test
    void snapshotContainsNoFileOutsideTheManifest() throws IOException {
        Set<String> listed = new TreeSet<>();
        for (String line : Files.readAllLines(UPSTREAM.resolve("MANIFEST.sha256"))) {
            listed.add(line.split(" {2}", 2)[1]);
        }
        Set<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(Files::isRegularFile)
                    .forEach(f -> present.add(UPSTREAM.relativize(f).toString().replace('\\', '/')));
        }
        assertEquals(listed, present);
    }

    @Test
    void manifestListsExactlyTheFilesInTheFileList() throws IOException {
        Set<String> fileList = new TreeSet<>();
        for (String line : Files.readAllLines(UPSTREAM.resolve("FILES"))) {
            if (!line.isBlank() && !line.startsWith("#")) fileList.add(line.trim());
        }
        Set<String> listed = new TreeSet<>();
        for (String line : Files.readAllLines(UPSTREAM.resolve("MANIFEST.sha256"))) {
            listed.add(line.split(" {2}", 2)[1]);
        }
        assertEquals(fileList, listed);
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
