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

/**
 * What Siyo source cannot observe: the {@code SiyoArray} header
 * ({@code { i32 tag, i32 len, i32 cap, ptr items }}) growing by doubling, and
 * the boxed-int cache for -128..127, which, like Java's {@code Integer} cache,
 * returns one shared box per value instead of allocating.
 */
@Tag("native")
class RuntimeArraysNativeTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static String run(String name, String program) throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("runtime-arrays"));
        Path ll = work.resolve(name + ".ll");
        Files.writeString(ll, """
                declare ptr @siyo_rt_array_new(i32)
                declare void @siyo_rt_array_push(ptr, ptr)
                declare ptr @siyo_rt_box_int(i32)
                declare void @siyo_rt_print_int(i32)
                declare void @siyo_rt_print_bool(i1 zeroext)
                declare void @siyo_rt_print_newline()

                ; Prints tag, len and cap of an array, one per line.
                define internal void @header(ptr %a) {
                  %tag = load i32, ptr %a
                  call void @siyo_rt_print_int(i32 %tag)
                  call void @siyo_rt_print_newline()
                  %len.at = getelementptr inbounds i8, ptr %a, i64 4
                  %len = load i32, ptr %len.at
                  call void @siyo_rt_print_int(i32 %len)
                  call void @siyo_rt_print_newline()
                  %cap.at = getelementptr inbounds i8, ptr %a, i64 8
                  %cap = load i32, ptr %cap.at
                  call void @siyo_rt_print_int(i32 %cap)
                  call void @siyo_rt_print_newline()
                  ret void
                }

                """ + program, StandardCharsets.UTF_8);
        Path exe = work.resolve(name);
        clang.compile(ll, exe);
        NativeRunner.Run run = NativeRunner.run(List.of(exe.toString()), work, Map.of("LC_ALL", "C"));
        assertEquals("", run.stderr());
        assertEquals(0, run.exitCode());
        return new String(run.stdout(), StandardCharsets.UTF_8);
    }

    @Test
    void arraysGrowByDoubling() throws Exception {
        String stdout = run("growth", """
                define void @siyo_program() {
                  %a = call ptr @siyo_rt_array_new(i32 0)
                  call void @header(ptr %a)
                  call void @siyo_rt_array_push(ptr %a, ptr null)
                  call void @header(ptr %a)
                  call void @siyo_rt_array_push(ptr %a, ptr null)
                  call void @siyo_rt_array_push(ptr %a, ptr null)
                  call void @siyo_rt_array_push(ptr %a, ptr null)
                  call void @siyo_rt_array_push(ptr %a, ptr null)
                  call void @header(ptr %a)
                  %b = call ptr @siyo_rt_array_new(i32 3)
                  call void @header(ptr %b)
                  ret void
                }
                """);
        // Empty: no items yet; then a minimum of 4, doubled to 8.
        assertEquals("5\n0\n0\n5\n1\n4\n5\n5\n8\n5\n0\n3\n", stdout);
    }

    @Test
    void smallIntsAreBoxedOnce() throws Exception {
        String stdout = run("box-cache", """
                define internal void @same(i32 %v) {
                  %a = call ptr @siyo_rt_box_int(i32 %v)
                  %b = call ptr @siyo_rt_box_int(i32 %v)
                  %same = icmp eq ptr %a, %b
                  call void @siyo_rt_print_bool(i1 zeroext %same)
                  call void @siyo_rt_print_newline()
                  ret void
                }
                define void @siyo_program() {
                  call void @same(i32 -129)
                  call void @same(i32 -128)
                  call void @same(i32 0)
                  call void @same(i32 127)
                  call void @same(i32 128)
                  ret void
                }
                """);
        assertEquals("false\ntrue\ntrue\ntrue\nfalse\n", stdout);
    }
}
