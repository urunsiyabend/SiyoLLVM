package siyollvm.toolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lookup and version checks, against fake clang scripts. */
class ClangTest {
    @TempDir
    Path dir;

    private Path fakeClang(String name, String versionLine) throws Exception {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/sh\necho '" + versionLine + "'\necho 'Target: x86_64-pc-linux-gnu'\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    @Test
    void explicitPathWinsOverEnvironmentAndPath() throws Exception {
        Path explicit = fakeClang("explicit-clang", "clang version 19.1.7");
        Path fromEnv = fakeClang("env-clang", "clang version 18.0.0");
        Clang clang = Clang.locate(Optional.of(explicit), Map.of("SIYOLLVM_CLANG", fromEnv.toString(), "PATH", dir.toString()));
        assertEquals(explicit, clang.executable());
        assertEquals(19, clang.majorVersion());
    }

    @Test
    void environmentVariableWinsOverPath() throws Exception {
        Path fromEnv = fakeClang("env-clang", "Ubuntu clang version 18.1.3 (1ubuntu1)");
        fakeClang("clang", "clang version 19.1.7");
        Clang clang = Clang.locate(Optional.empty(), Map.of("SIYOLLVM_CLANG", fromEnv.toString(), "PATH", dir.toString()));
        assertEquals(fromEnv, clang.executable());
        assertEquals(18, clang.majorVersion());
    }

    @Test
    void environmentVariableMayNameACommandOnPath() throws Exception {
        Path named = fakeClang("clang-19", "clang version 19.1.7");
        Clang clang = Clang.locate(Optional.empty(), Map.of("SIYOLLVM_CLANG", "clang-19", "PATH", dir.toString()));
        assertEquals(named, clang.executable());
    }

    @Test
    void clangOnPathIsTheFallback() throws Exception {
        Path onPath = fakeClang("clang", "clang version 17.0.6");
        Clang clang = Clang.locate(Optional.empty(), Map.of("PATH", "/nonexistent:" + dir));
        assertEquals(onPath, clang.executable());
    }

    @Test
    void missingClangExplainsTheLookupOrder() {
        ToolchainException e = assertThrows(ToolchainException.class,
                () -> Clang.locate(Optional.empty(), Map.of("PATH", dir.toString())));
        assertTrue(e.getMessage().contains("--clang"), e.getMessage());
        assertTrue(e.getMessage().contains("SIYOLLVM_CLANG"), e.getMessage());
        assertTrue(e.getMessage().contains("PATH"), e.getMessage());
    }

    @Test
    void explicitPathThatDoesNotExistIsAnError() {
        ToolchainException e = assertThrows(ToolchainException.class,
                () -> Clang.locate(Optional.of(dir.resolve("nope")), Map.of()));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    @Test
    void clangOlderThan15IsRejected() throws Exception {
        Path old = fakeClang("clang", "clang version 14.0.6");
        ToolchainException e = assertThrows(ToolchainException.class,
                () -> Clang.locate(Optional.of(old), Map.of()));
        assertTrue(e.getMessage().contains("14"), e.getMessage());
        assertTrue(e.getMessage().contains("15"), e.getMessage());
    }

    @Test
    void somethingThatIsNotClangIsRejected() throws Exception {
        Path notClang = fakeClang("clang", "gcc (GCC) 13.2.0");
        assertThrows(ToolchainException.class, () -> Clang.locate(Optional.of(notClang), Map.of()));
    }

    @Test
    void failedCompileReportsClangOutput() throws Exception {
        Path failing = dir.resolve("clang");
        Files.writeString(failing, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then echo 'clang version 19.1.7'; exit 0; fi
                echo 'error: broken IR' >&2
                exit 1
                """);
        Files.setPosixFilePermissions(failing, PosixFilePermissions.fromString("rwxr-xr-x"));
        Clang clang = Clang.locate(Optional.of(failing), Map.of());
        ToolchainException e = assertThrows(ToolchainException.class,
                () -> clang.compile(dir.resolve("x.ll"), dir.resolve("x")));
        assertTrue(e.getMessage().contains("error: broken IR"), e.getMessage());
    }

    /** A clang that records its arguments (one per line) and whether the runtime source existed. */
    private Path recordingClang(String compileBody) throws Exception {
        Path recording = dir.resolve("clang");
        Files.writeString(recording, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then echo 'clang version 19.1.7'; exit 0; fi\n"
                + "for a; do echo \"$a\"; done > '" + dir.resolve("args.txt") + "'\n"
                + "if [ -f \"${10}\" ] && [ -f \"$(dirname \"${10}\")/siyo_rt.h\" ]; then echo yes; else echo no; fi > '"
                + dir.resolve("runtime-present.txt") + "'\n"
                + compileBody);
        Files.setPosixFilePermissions(recording, PosixFilePermissions.fromString("rwxr-xr-x"));
        return recording;
    }

    private List<String> recordedArgs() throws IOException {
        return Files.readAllLines(dir.resolve("args.txt"));
    }

    private List<String> runtimeDirectories(Path parent) throws IOException {
        try (var entries = Files.list(parent)) {
            return entries.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".siyo-rt")).toList();
        }
    }

    // Release clang skips the IR verifier by default; malformed IR then crashes
    // or miscompiles instead of being reported. The C runtime is compiled beside
    // the program IR from a temporary copy next to the executable.
    @Test
    void compileVerifiesTheIrAndLinksTheCRuntime() throws Exception {
        Path out = Files.createDirectories(dir.resolve("out"));
        Path ll = dir.resolve("x.ll");
        Path exe = out.resolve("x");
        Clang.locate(Optional.of(recordingClang("")), Map.of()).compile(ll, exe);
        List<String> args = recordedArgs();
        assertEquals(13, args.size(), args.toString());
        Path runtime = Path.of(args.get(9));
        assertEquals("siyo_rt.c", runtime.getFileName().toString());
        assertEquals(out.toAbsolutePath(), runtime.getParent().getParent());
        assertTrue(runtime.getParent().getFileName().toString().startsWith(".siyo-rt"), runtime.toString());
        assertEquals(List.of("-O1", "-fverify-intermediate-code", "-w", "-x", "ir", ll.toString(), "-x", "c",
                "-std=c11", runtime.toString(), "-pthread", "-o", exe.toString()), args);
        assertEquals("yes", Files.readString(dir.resolve("runtime-present.txt")).strip());
        assertEquals(List.of(), runtimeDirectories(out));
    }

    @Test
    void failedCompileRemovesTheRuntimeCopy() throws Exception {
        Path out = Files.createDirectories(dir.resolve("out"));
        Clang clang = Clang.locate(Optional.of(recordingClang("exit 1\n")), Map.of());
        assertThrows(ToolchainException.class, () -> clang.compile(dir.resolve("x.ll"), out.resolve("x")));
        assertEquals("yes", Files.readString(dir.resolve("runtime-present.txt")).strip());
        assertEquals(List.of(), runtimeDirectories(out));
    }

    // Regression (independent CLI review): readAllBytes() blocked until the
    // child closed its output, so a hanging clang ran past the timeout.
    private static final Clang.Timeouts SHORT = new Clang.Timeouts(
            java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(500));

    private Path hangingClang(boolean hangOnVersion) throws Exception {
        Path script = dir.resolve(hangOnVersion ? "clang-hang-version" : "clang-hang-compile");
        Files.writeString(script, "#!/bin/sh\n"
                + (hangOnVersion ? "" : "if [ \"$1\" = \"--version\" ]; then echo 'clang version 19.1.7'; exit 0; fi\n")
                + "sleep 60 &\n"
                + "echo $! > '" + dir.resolve("child.pid") + "'\n"
                + "echo $$ > '" + dir.resolve("parent.pid") + "'\n"
                + "wait\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private void assertProcessTreeGone() throws Exception {
        for (String name : java.util.List.of("parent.pid", "child.pid")) {
            long pid = Long.parseLong(Files.readString(dir.resolve(name)).strip());
            java.util.Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isPresent()) handle.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true), name + " still running");
        }
    }

    @Test
    void hangingCompileIsStoppedAtTheTimeoutWithItsChildren() throws Exception {
        Clang clang = Clang.locate(Optional.of(hangingClang(false)), Map.of(), SHORT);
        long start = System.nanoTime();
        ToolchainException e = assertThrows(ToolchainException.class,
                () -> clang.compile(dir.resolve("x.ll"), dir.resolve("x")));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 10_000, "took " + millis + " ms");
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertProcessTreeGone();
    }

    @Test
    void hangingVersionCheckIsStoppedAtTheTimeout() throws Exception {
        Path script = hangingClang(true);
        long start = System.nanoTime();
        assertThrows(ToolchainException.class, () -> Clang.locate(Optional.of(script), Map.of(), SHORT));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 10_000, "took " + millis + " ms");
        assertProcessTreeGone();
    }

    @Test
    void defaultTimeoutsAreDocumentedValues() {
        assertEquals(java.time.Duration.ofSeconds(30), Clang.Timeouts.DEFAULT.version());
        assertEquals(java.time.Duration.ofSeconds(600), Clang.Timeouts.DEFAULT.compile());
    }
}
