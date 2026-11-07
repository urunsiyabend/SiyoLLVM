package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import siyollvm.Compiler;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;
import siyollvm.toolchain.RuntimeSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The C runtime compiled on its own terms rather than through
 * {@link Clang#compile}: once with every warning an error, and once as a
 * separate copy whose case-mapping locale cannot be loaded.
 */
@Tag("native")
class RuntimeBuildNativeTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static Path work(String name) throws Exception {
        return Files.createDirectories(TestPaths.tempRoot().resolve("runtime-build").resolve(name));
    }

    private static NativeRunner.Run clang(Path work, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(clang.executable().toString());
        command.addAll(arguments);
        return NativeRunner.run(command, work, Map.of());
    }

    @Test
    void runtimeIsWarningFreeC11() throws Exception {
        Path work = work("warnings");
        Path runtime = RuntimeSource.writeTo(work);
        NativeRunner.Run run = clang(work, List.of("-std=c11", "-Wall", "-Wextra", "-Werror", "-fsyntax-only",
                runtime.toString()));
        assertEquals("", run.stderr());
        assertEquals(0, run.exitCode());
    }

    /**
     * The host's glibc (2.43) loads C.UTF-8 even with LOCPATH at an empty or
     * corrupt directory (while en_US.UTF-8 then fails), so no environment
     * makes newlocale fail for it. This copy of the runtime is compiled with
     * {@code SIYO_RT_CASE_LOCALE} naming a locale that does not exist, so the
     * real newlocale call fails and the real error path runs. ASCII and the
     * two fixed special cases need no locale and still map.
     */
    @Test
    void nonAsciiCaseMappingWithoutTheLocaleStops() throws Exception {
        Path work = work("no-locale");
        String source = """
                fn main() {
                    println(toUpper("abc"))
                    println(toLower("XYZ"))
                    println(toUpper("ß"))
                    println(toLower("İ"))
                    println(toUpper("é"))
                    println("unreachable")
                }
                """;
        Compiler.IrResult ir = Compiler.toIr("no_locale.siyo", source);
        assertTrue(ir.diagnostics().isEmpty(), ir.diagnostics().toString());
        Path ll = work.resolve("no_locale.ll");
        Files.writeString(ll, ir.ir(), StandardCharsets.UTF_8);
        Path runtime = RuntimeSource.writeTo(work.resolve("rt"));
        Path exe = work.resolve("no_locale");
        NativeRunner.Run build = clang(work, List.of("-O1", "-fverify-intermediate-code", "-w",
                "-DSIYO_RT_CASE_LOCALE=\"siyollvm-no-such-locale\"",
                "-x", "ir", ll.toString(), "-x", "c", "-std=c11", runtime.toString(),
                "-pthread", "-o", exe.toString()));
        assertEquals(0, build.exitCode(), build.stderr());

        NativeRunner.Run run = NativeRunner.run(List.of(exe.toString()), work, Map.of("LC_ALL", "C"));
        assertEquals("ABC\nxyz\nSS\ni̇\n", new String(run.stdout(), StandardCharsets.UTF_8));
        assertEquals("SiyoLLVM runtime: toUpper/toLower of non-ASCII text needs the siyollvm-no-such-locale"
                + " locale, which is not available\n", run.stderr());
        assertEquals(1, run.exitCode());
    }
}
