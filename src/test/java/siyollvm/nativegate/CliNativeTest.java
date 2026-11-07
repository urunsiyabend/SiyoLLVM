package siyollvm.nativegate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import siyollvm.TestPaths;
import siyollvm.toolchain.Clang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real command, in its own JVM, under the C locale, building and running a program. */
@Tag("native")
class CliNativeTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static List<String> siyollvm(String... args) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new java.util.ArrayList<>(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), "siyollvm.cli.Main"));
        command.addAll(List.of(args));
        return command;
    }

    @Test
    void buildsUnicodeSourceUnderCLocale() throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("cli-native"));
        Path source = work.resolve("unicode.siyo");
        Files.writeString(source, "fn selam(ad: string, ü: int, ö: int) -> int { println(ad) return ü - ö }\n"
                + "fn main() { println(selam(\"Dünya ✓\", 5, 3)) }\n", StandardCharsets.UTF_8);
        Path exe = work.resolve("unicode-program");
        Path ll = work.resolve("unicode-program.ll");

        NativeRunner.Run compile = NativeRunner.run(siyollvm("build", source.toString(), "-o", exe.toString(),
                "--clang", clang.executable().toString(), "--emit-llvm", ll.toString()), work, Map.of("LC_ALL", "C"));
        assertEquals(0, compile.exitCode(), compile.stderr());
        assertTrue(Files.isRegularFile(ll));

        NativeRunner.Run program = NativeRunner.run(List.of(exe.toString()), work, Map.of("LC_ALL", "C"));
        assertEquals("Dünya ✓\n2\n", new String(program.stdout(), StandardCharsets.UTF_8));
        assertEquals(0, program.exitCode());
    }

    @Test
    void linksByHandFromEmitLlvmAndEmitRuntime() throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("cli-manual"));
        Path source = work.resolve("hello.siyo");
        Files.writeString(source, "fn main() { println(\"manuel\") }\n", StandardCharsets.UTF_8);
        Path ll = work.resolve("prog.ll");
        NativeRunner.Run emitIr = NativeRunner.run(siyollvm("emit-llvm", source.toString(), "-o", ll.toString()), work, Map.of());
        assertEquals(0, emitIr.exitCode(), emitIr.stderr());
        Path rt = work.resolve("rt");
        NativeRunner.Run emitRt = NativeRunner.run(siyollvm("emit-runtime", rt.toString()), work, Map.of());
        assertEquals(0, emitRt.exitCode(), emitRt.stderr());

        Path exe = work.resolve("prog");
        NativeRunner.Run link = NativeRunner.run(List.of(clang.executable().toString(), "-O1", "-x", "ir", ll.toString(),
                "-x", "c", "-std=c11", rt.resolve("siyo_rt.c").toString(), "-pthread", "-o", exe.toString()), work, Map.of());
        assertEquals(0, link.exitCode(), link.stderr());
        NativeRunner.Run program = NativeRunner.run(List.of(exe.toString()), work, Map.of());
        assertEquals("manuel\n", new String(program.stdout(), StandardCharsets.UTF_8));
        assertEquals(0, program.exitCode());
    }

    @Test
    void buildsAMultiFileProgramFromAnUnrelatedWorkingDirectory() throws Exception {
        Path root = Files.createDirectories(TestPaths.tempRoot().resolve("cli-modules"));
        Path project = Files.createDirectories(root.resolve("proj"));
        Files.createDirectories(project.resolve("lib"));
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        Files.writeString(project.resolve("lib/util.siyo"),
                "mut calls = 0\nfn init() { println(\"util init\") }\nfn twice(x: int) -> int { calls = calls + 1\n x * 2 }\n");
        Path source = root.resolve("proj/main.siyo");
        Files.writeString(source, "import \"lib/util\"\nfn main() { println(util.twice(21))\n println(util.calls) }\n");
        Path exe = root.resolve("modules-program");

        // Relative to the working directory, which is neither the project nor its parent.
        NativeRunner.Run compile = NativeRunner.run(siyollvm("build", "../proj/main.siyo", "-o", exe.toString(),
                "--clang", clang.executable().toString()), elsewhere, Map.of());
        assertEquals(0, compile.exitCode(), compile.stderr());

        NativeRunner.Run program = NativeRunner.run(List.of(exe.toString()), elsewhere, Map.of());
        assertEquals("util init\n42\n1\n", new String(program.stdout(), StandardCharsets.UTF_8));
        assertEquals(0, program.exitCode());
    }
}
