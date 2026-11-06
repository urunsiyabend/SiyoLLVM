package siyollvm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(Map<String, String> env, String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env);
    }

    private int run(String... args) {
        return run(Map.of(), args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path source(String name, String text) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void emitLlvmWritesIrToStdout() throws Exception {
        Path file = source("hello.siyo", "fn main() { println(\"hi\") }");
        assertEquals(0, run("emit-llvm", file.toString()));
        assertTrue(stdout().contains("define i32 @main()"), stdout());
        assertEquals("", stderr());
    }

    @Test
    void emitLlvmWritesIrToFile() throws Exception {
        Path file = source("hello.siyo", "fn main() { println(\"hi\") }");
        Path ll = dir.resolve("out.ll");
        assertEquals(0, run("emit-llvm", file.toString(), "-o", ll.toString()));
        assertTrue(Files.readString(ll).contains("define i32 @main()"));
        assertEquals("", stdout());
    }

    @Test
    void unsupportedProgramReportsAndWritesNothing() throws Exception {
        Path file = source("bad.siyo", "fn main() {\n  println(1.5)\n  mut a = [1]\n}");
        Path ll = dir.resolve("bad.ll");
        assertEquals(1, run("emit-llvm", file.toString(), "-o", ll.toString()));
        assertEquals("bad.siyo(2, 11): [llvm] float values are not supported by SiyoLLVM\n"
                + "bad.siyo(3, 11): [llvm] arrays are not supported by SiyoLLVM\n", stderr());
        assertFalse(Files.exists(ll));
        assertEquals("", stdout());
    }

    @Test
    void syntaxErrorExitsWithOne() throws Exception {
        Path file = source("syntax.siyo", "fn main( {");
        assertEquals(1, run("emit-llvm", file.toString()));
        assertTrue(stderr().startsWith("syntax.siyo(1, "), stderr());
    }

    @Test
    void malformedUtf8IsAClearError() throws Exception {
        Path file = dir.resolve("latin1.siyo");
        Files.write(file, new byte[]{'f', 'n', ' ', (byte) 0xE9, '(', ')', ' ', '{', '}'});
        assertEquals(1, run("emit-llvm", file.toString()));
        assertEquals("error: " + file + " is not valid UTF-8\n", stderr());
    }

    @Test
    void missingFileIsAClearError() {
        Path file = dir.resolve("missing.siyo");
        assertEquals(1, run("emit-llvm", file.toString()));
        assertEquals("error: cannot read " + file + ": file not found\n", stderr());
    }

    @Test
    void buildWithMissingClangExplainsLookup() throws Exception {
        Path file = source("hello.siyo", "fn main() { println(\"hi\") }");
        Path exe = dir.resolve("hello");
        assertEquals(1, run(Map.of("PATH", dir.toString()), "build", file.toString(), "-o", exe.toString()));
        assertTrue(stderr().startsWith("error: clang not found."), stderr());
        assertFalse(Files.exists(exe));
    }

    @Test
    void buildWithUnusableExplicitClangFails() throws Exception {
        Path file = source("hello.siyo", "fn main() { println(\"hi\") }");
        assertEquals(1, run("build", file.toString(), "--clang", dir.resolve("no-clang").toString()));
        assertTrue(stderr().contains("no-clang"), stderr());
    }

    @Test
    void buildReportsDiagnosticsBeforeLookingForClang() throws Exception {
        Path file = source("bad.siyo", "fn main() { println(1.5) }");
        assertEquals(1, run(Map.of("PATH", dir.toString()), "build", file.toString()));
        assertTrue(stderr().contains("[llvm] float values"), stderr());
        assertFalse(stderr().contains("clang"), stderr());
    }

    @Test
    void versionAndHelp() {
        assertEquals(0, run("--version"));
        assertTrue(stdout().startsWith("siyollvm "), stdout());
        out.reset();
        assertEquals(0, run("--help"));
        assertTrue(stdout().contains("emit-llvm"), stdout());
        assertTrue(stdout().contains("build"), stdout());
        assertTrue(stdout().contains("SIYOLLVM_CLANG"), stdout());
    }

    @Test
    void unknownCommandOrMissingArgumentIsAUsageError() {
        assertEquals(2, run("compile"));
        assertTrue(stderr().contains("usage"), stderr());
        err.reset();
        assertEquals(2, run("emit-llvm"));
        err.reset();
        assertEquals(2, run("emit-llvm", "a.siyo", "-o"));
        err.reset();
        assertEquals(2, run());
    }

    // --- Regressions from the independent CLI review -------------------------

    @Test
    void optionValueThatIsAnOptionIsAUsageErrorAndWritesNothing() throws Exception {
        Path file = source("valid.siyo", "fn main() { println(1) }");
        Path stray = Path.of("--missing");
        Files.deleteIfExists(stray);
        try {
            assertEquals(2, run("emit-llvm", file.toString(), "-o", "--missing"));
            assertTrue(stderr().contains("-o needs a value"), stderr());
            assertFalse(Files.exists(stray), "wrote a file named after the option");
            for (String[] args : new String[][]{
                    {"build", file.toString(), "-o", "--clang", "x"},
                    {"build", file.toString(), "--emit-llvm", "-o"},
                    {"build", file.toString(), "--clang", "--emit-llvm", "x.ll"}}) {
                err.reset();
                assertEquals(2, run(args), String.join(" ", args));
            }
            assertFalse(Files.exists(Path.of("-o")));
            assertFalse(Files.exists(Path.of("x.ll")));
        } finally {
            Files.deleteIfExists(stray);
        }
    }

    @Test
    void outputThatIsTheSourceIsRefused() throws Exception {
        String text = "fn main() { println(1) }";
        Path file = source("same.siyo", text);
        assertEquals(2, run("emit-llvm", file.toString(), "-o", file.toString()));
        assertTrue(stderr().contains("would overwrite the source file"), stderr());
        assertEquals(text, Files.readString(file));
        err.reset();
        assertEquals(2, run("emit-llvm", file.toString(), "-o", dir.resolve("./sub/../same.siyo").toString()));
        assertEquals(text, Files.readString(file));
    }

    @Test
    void hardlinkAndSymlinkAliasesOfTheSourceAreRefused() throws Exception {
        String text = "fn main() { println(1) }";
        Path file = source("orig.siyo", text);
        Path hard = Files.createLink(dir.resolve("hard.ll"), file);
        Path soft = Files.createSymbolicLink(dir.resolve("soft.ll"), file);
        assertEquals(2, run("emit-llvm", file.toString(), "-o", hard.toString()));
        assertEquals(2, run("emit-llvm", file.toString(), "-o", soft.toString()));
        assertEquals(2, run("build", file.toString(), "-o", hard.toString()));
        assertEquals(2, run("build", file.toString(), "--emit-llvm", soft.toString()));
        assertEquals(text, Files.readString(file));
        assertEquals(text, Files.readString(hard));
        assertTrue(Files.isSymbolicLink(soft));
    }

    @Test
    void buildRefusesTheSameFileForExecutableAndIr() throws Exception {
        Path file = source("collide.siyo", "fn main() { println(1) }");
        Path target = dir.resolve("out");
        assertEquals(2, run(Map.of("PATH", dir.toString()), "build", file.toString(), "-o", target.toString(),
                "--emit-llvm", dir.resolve("x/../out").toString()));
        assertTrue(stderr().contains("same file"), stderr());
        assertFalse(Files.exists(target));
        err.reset();
        Path existing = Files.writeString(dir.resolve("existing"), "keep");
        Path link = Files.createLink(dir.resolve("existing-link"), existing);
        assertEquals(2, run(Map.of("PATH", dir.toString()), "build", file.toString(), "-o", existing.toString(),
                "--emit-llvm", link.toString()));
        assertEquals("keep", Files.readString(existing));
    }

    @Test
    void emitLlvmMayReplaceAnUnrelatedExistingFile() throws Exception {
        Path file = source("ok.siyo", "fn main() { println(1) }");
        Path ll = Files.writeString(dir.resolve("old.ll"), "old");
        assertEquals(0, run("emit-llvm", file.toString(), "-o", ll.toString()));
        assertTrue(Files.readString(ll).contains("define i32 @main()"));
    }

    private Path fakeClang(String compileBody) throws Exception {
        Path clang = dir.resolve("fake-clang");
        Files.writeString(clang, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then echo 'clang version 19.1.7'; exit 0; fi\n"
                + "for last; do :; done\n"          // $last is the -o target
                + compileBody);
        Files.setPosixFilePermissions(clang, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return clang;
    }

    private java.util.List<String> leftovers() throws Exception {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.contains(".siyollvm")).toList();
        }
    }

    @Test
    void failedBuildLeavesNoPartialExecutable() throws Exception {
        Path file = source("fail.siyo", "fn main() { println(1) }");
        Path clang = fakeClang("echo partial > \"$last\"\necho 'error: boom' >&2\nexit 1\n");
        Path exe = dir.resolve("fail-exe");
        assertEquals(1, run("build", file.toString(), "-o", exe.toString(), "--clang", clang.toString()));
        assertTrue(stderr().contains("error: boom"), stderr());
        assertFalse(Files.exists(exe));
        assertEquals(java.util.List.of(), leftovers());
    }

    @Test
    void failedBuildKeepsAnExistingExecutable() throws Exception {
        Path file = source("keep.siyo", "fn main() { println(1) }");
        Path clang = fakeClang("echo partial > \"$last\"\nexit 1\n");
        Path exe = Files.writeString(dir.resolve("keep-exe"), "previous");
        assertEquals(1, run("build", file.toString(), "-o", exe.toString(), "--clang", clang.toString()));
        assertEquals("previous", Files.readString(exe));
    }

    @Test
    void successfulBuildMovesTheExecutableIntoPlace() throws Exception {
        Path file = source("good.siyo", "fn main() { println(1) }");
        Path clang = fakeClang("echo built > \"$last\"\nexit 0\n");
        Path exe = dir.resolve("good-exe");
        assertEquals(0, run("build", file.toString(), "-o", exe.toString(), "--clang", clang.toString()));
        assertEquals("built\n", Files.readString(exe));
        assertEquals(java.util.List.of(), leftovers());
    }

    @Test
    void hangingClangTimesOutAndCleansUp() throws Exception {
        Path file = source("hang.siyo", "fn main() { println(1) }");
        Path clang = fakeClang("echo partial > \"$last\"\nsleep 60\n");
        Path exe = dir.resolve("hang-exe");
        long start = System.nanoTime();
        int status = Main.run(new String[]{"build", file.toString(), "-o", exe.toString(), "--clang", clang.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                Map.of(), new siyollvm.toolchain.Clang.Timeouts(java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofMillis(500)));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(1, status);
        assertTrue(millis < 10_000, "took " + millis + " ms");
        assertTrue(stderr().contains("did not finish"), stderr());
        assertFalse(Files.exists(exe));
        assertEquals(java.util.List.of(), leftovers());
    }
}
