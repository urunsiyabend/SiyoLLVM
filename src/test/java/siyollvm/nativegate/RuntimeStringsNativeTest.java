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
 * The runtime's string constructors, driven from hand-written IR: lone
 * surrogates cannot come from F1 Siyo source yet, and the UTF-16 length is
 * read straight from the {@code SiyoStr} header. Each string prints as its
 * text, its UTF-16 length and its byte length, one per line.
 */
@Tag("native")
class RuntimeStringsNativeTest {
    private static Clang clang;

    @BeforeAll
    static void requireClang() throws Exception {
        clang = NativeRunner.clang();
    }

    private static final String IR = """
            declare ptr @siyo_rt_str_concat(ptr, ptr)
            declare ptr @siyo_rt_str_from_int(i32)
            declare ptr @siyo_rt_str_from_long(i64)
            declare ptr @siyo_rt_str_from_bool(i1 zeroext)
            declare ptr @siyo_rt_str_of_string(ptr)
            declare void @siyo_rt_print_str(ptr)
            declare void @siyo_rt_print_int(i32)
            declare void @siyo_rt_print_long(i64)
            declare void @siyo_rt_print_newline()

            ; "a" + U+D83D, "\\uDE00" + "b" (UTF-16 length not computed), and both surrogates alone.
            @high = private constant { i32, i32, i64, [4 x i8] } { i32 1, i32 2, i64 4, [4 x i8] c"a\\ED\\A0\\BD" }, align 8
            @low = private constant { i32, i32, i64, [4 x i8] } { i32 1, i32 -1, i64 4, [4 x i8] c"\\ED\\B8\\80b" }, align 8
            @high.only = private constant { i32, i32, i64, [3 x i8] } { i32 1, i32 1, i64 3, [3 x i8] c"\\ED\\A0\\BD" }, align 8
            @low.only = private constant { i32, i32, i64, [3 x i8] } { i32 1, i32 1, i64 3, [3 x i8] c"\\ED\\B8\\80" }, align 8

            define internal void @show(ptr %s) {
              call void @siyo_rt_print_str(ptr %s)
              call void @siyo_rt_print_newline()
              %u.at = getelementptr inbounds i8, ptr %s, i64 4
              %u = load i32, ptr %u.at
              call void @siyo_rt_print_int(i32 %u)
              call void @siyo_rt_print_newline()
              %n.at = getelementptr inbounds i8, ptr %s, i64 8
              %n = load i64, ptr %n.at
              call void @siyo_rt_print_long(i64 %n)
              call void @siyo_rt_print_newline()
              ret void
            }

            define void @siyo_program() {
              %joined = call ptr @siyo_rt_str_concat(ptr @high, ptr @low)
              call void @show(ptr %joined)
              %pair = call ptr @siyo_rt_str_concat(ptr @high.only, ptr @low.only)
              call void @show(ptr %pair)
              %apart = call ptr @siyo_rt_str_concat(ptr @low, ptr @high)
              call void @show(ptr %apart)
              %null.left = call ptr @siyo_rt_str_concat(ptr null, ptr @high)
              call void @show(ptr %null.left)
              %nulls = call ptr @siyo_rt_str_concat(ptr null, ptr null)
              call void @show(ptr %nulls)
              %int = call ptr @siyo_rt_str_from_int(i32 -2147483648)
              call void @show(ptr %int)
              %long = call ptr @siyo_rt_str_from_long(i64 -9223372036854775808)
              call void @show(ptr %long)
              %bool = call ptr @siyo_rt_str_from_bool(i1 zeroext false)
              call void @show(ptr %bool)
              %of.null = call ptr @siyo_rt_str_of_string(ptr null)
              call void @show(ptr %of.null)
              %of.low = call ptr @siyo_rt_str_of_string(ptr @low)
              call void @show(ptr %of.low)
              ret void
            }
            """;

    @Test
    void constructorsJoinSurrogatesAndRecordLengths() throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("runtime-strings"));
        Path ll = work.resolve("strings.ll");
        Files.writeString(ll, IR, StandardCharsets.UTF_8);
        Path exe = work.resolve("strings");
        clang.compile(ll, exe);
        NativeRunner.Run run = NativeRunner.run(List.of(exe.toString()), work, Map.of("LC_ALL", "C"));
        assertEquals("", run.stderr());
        assertEquals(0, run.exitCode());
        assertEquals("""
                a😀b
                4
                6
                😀
                2
                4
                ?ba?
                4
                8
                nulla?
                6
                8
                nullnull
                8
                8
                -2147483648
                11
                11
                -9223372036854775808
                20
                20
                false
                5
                5
                null
                4
                4
                ?b
                -1
                4
                """, new String(run.stdout(), StandardCharsets.UTF_8));
    }
}
