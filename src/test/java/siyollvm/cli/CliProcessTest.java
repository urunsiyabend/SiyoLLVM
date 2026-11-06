package siyollvm.cli;

import org.junit.jupiter.api.Test;
import siyollvm.TestPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command in its own JVM under LC_ALL=C (no clang needed). */
class CliProcessTest {

    record Result(int exitCode, String stdout, String stderr) {}

    static Result runUnderCLocale(Path directory, String... args) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), "siyollvm.cli.Main"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().keySet().removeIf(k -> k.equals("LANG") || k.startsWith("LC_"));
        builder.environment().put("LC_ALL", "C");
        Path out = Files.createTempFile(directory, "out", ".txt");
        Path err = Files.createTempFile(directory, "err", ".txt");
        Process process = builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }

    private static Path work() throws Exception {
        return Files.createDirectories(TestPaths.tempRoot().resolve("cli-process"));
    }

    @Test
    void unicodeDiagnosticsAreUtf8UnderCLocale() throws Exception {
        Path work = work();
        Files.writeString(work.resolve("names.siyo"), "fn main() { println(bilinmeyenİsim) }\n", StandardCharsets.UTF_8);
        Result result = runUnderCLocale(work, "emit-llvm", "names.siyo");
        assertEquals(1, result.exitCode());
        assertEquals("names.siyo(1, 21): Name 'bilinmeyenİsim' does not exist\n", result.stderr());
    }

    @Test
    void unicodeSourceCompilesUnderCLocale() throws Exception {
        Path work = work();
        Files.writeString(work.resolve("text.siyo"), "fn ünlü(ç: string) { println(ç) }\nfn main() { ünlü(\"çğış ✓\") }\n",
                StandardCharsets.UTF_8);
        Result result = runUnderCLocale(work, "emit-llvm", "text.siyo");
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("c\"\\C3\\A7\\C4\\9F"), result.stdout());
    }

    @Test
    void nonAsciiFileNameTheJvmCannotDecodeIsAClearError() throws Exception {
        Path work = work();
        Files.writeString(work.resolve("şehir.siyo"), "fn main() { println(1) }\n", StandardCharsets.UTF_8);
        Result result = runUnderCLocale(work, "emit-llvm", "şehir.siyo");
        assertEquals(1, result.exitCode(), result.stderr());
        assertTrue(result.stderr().startsWith("error: the file name"), result.stderr());
        assertTrue(result.stderr().contains("UTF-8 locale"), result.stderr());
    }
}
