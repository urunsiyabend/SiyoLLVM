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

    /** Declarations, {@code @show} and the string constants every program here uses. */
    private static final String PRELUDE = """
            declare ptr @siyo_rt_str_concat(ptr, ptr)
            declare ptr @siyo_rt_str_from_int(i32)
            declare ptr @siyo_rt_str_from_long(i64)
            declare ptr @siyo_rt_str_from_bool(i1 zeroext)
            declare ptr @siyo_rt_str_of_string(ptr)
            declare void @siyo_rt_print_str(ptr)
            declare void @siyo_rt_print_int(i32)
            declare void @siyo_rt_print_long(i64)
            declare void @siyo_rt_print_bool(i1 zeroext)
            declare void @siyo_rt_print_newline()

            ; "a" + U+D83D, "\\uDE00" + "b", and both surrogates alone.
            @high.b = private constant [4 x i8] c"a\\ED\\A0\\BD"
            @high = private constant { i32, i32, i64, ptr, ptr } { i32 1, i32 2, i64 4, ptr @high.b, ptr null }, align 8
            @low.b = private constant [4 x i8] c"\\ED\\B8\\80b"
            @low = private constant { i32, i32, i64, ptr, ptr } { i32 1, i32 2, i64 4, ptr @low.b, ptr null }, align 8
            @high.only.b = private constant [3 x i8] c"\\ED\\A0\\BD"
            @high.only = private constant { i32, i32, i64, ptr, ptr } { i32 1, i32 1, i64 3, ptr @high.only.b, ptr null }, align 8
            @low.only.b = private constant [3 x i8] c"\\ED\\B8\\80"
            @low.only = private constant { i32, i32, i64, ptr, ptr } { i32 1, i32 1, i64 3, ptr @low.only.b, ptr null }, align 8
            @ab.b = private constant [2 x i8] c"ab"
            @ab = private constant { i32, i32, i64, ptr, ptr } { i32 1, i32 2, i64 2, ptr @ab.b, ptr null }, align 8
            @one.b = private constant [1 x i8] c"1"
            @one = private constant { i32, i32, i64, ptr, ptr } { i32 1, i32 1, i64 1, ptr @one.b, ptr null }, align 8
            @two.b = private constant [1 x i8] c"2"
            @two = private constant { i32, i32, i64, ptr, ptr } { i32 1, i32 1, i64 1, ptr @two.b, ptr null }, align 8
            @three.b = private constant [1 x i8] c"3"
            @three = private constant { i32, i32, i64, ptr, ptr } { i32 1, i32 1, i64 1, ptr @three.b, ptr null }, align 8

            ; Prints the text, the UTF-16 length and the byte length, one per line.
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

            ; Prints whether two strings' data pointers are equal: whether one was appended in place.
            define internal void @same.data(ptr %a, ptr %b) {
              %a.at = getelementptr inbounds i8, ptr %a, i64 16
              %a.data = load ptr, ptr %a.at
              %b.at = getelementptr inbounds i8, ptr %b, i64 16
              %b.data = load ptr, ptr %b.at
              %same = icmp eq ptr %a.data, %b.data
              call void @siyo_rt_print_bool(i1 zeroext %same)
              call void @siyo_rt_print_newline()
              ret void
            }
            """;

    private static String run(String name, String program) throws Exception {
        Path work = Files.createDirectories(TestPaths.tempRoot().resolve("runtime-strings"));
        Path ll = work.resolve(name + ".ll");
        Files.writeString(ll, PRELUDE + program, StandardCharsets.UTF_8);
        Path exe = work.resolve(name);
        clang.compile(ll, exe);
        NativeRunner.Run run = NativeRunner.run(List.of(exe.toString()), work, Map.of("LC_ALL", "C"));
        assertEquals("", run.stderr());
        assertEquals(0, run.exitCode());
        return new String(run.stdout(), StandardCharsets.UTF_8);
    }

    @Test
    void constructorsJoinSurrogatesAndRecordLengths() throws Exception {
        String stdout = run("constructors", """
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
                """);
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
                2
                4
                """, stdout);
    }

    /**
     * {@code a + b} appends in place only when {@code a} ends at its buffer's
     * used mark; an older header over the same buffer then copies, so
     * {@code x + "1"} and {@code x + "2"} stay distinct. A surrogate pair
     * formed at the seam also copies, as the bytes before it change.
     */
    @Test
    void appendingKeepsEveryHeaderIntact() throws Exception {
        String stdout = run("aliasing", """
                define void @siyo_program() {
                  %x = call ptr @siyo_rt_str_concat(ptr @ab, ptr @ab)
                  %a = call ptr @siyo_rt_str_concat(ptr %x, ptr @one)
                  %b = call ptr @siyo_rt_str_concat(ptr %x, ptr @two)
                  %c = call ptr @siyo_rt_str_concat(ptr %a, ptr @three)
                  %d = call ptr @siyo_rt_str_concat(ptr %a, ptr @two)
                  call void @show(ptr %x)
                  call void @show(ptr %a)
                  call void @show(ptr %b)
                  call void @show(ptr %c)
                  call void @show(ptr %d)
                  call void @same.data(ptr %x, ptr %a)
                  call void @same.data(ptr %x, ptr %b)
                  call void @same.data(ptr %x, ptr %c)
                  call void @same.data(ptr %x, ptr %d)
                  %h = call ptr @siyo_rt_str_concat(ptr @ab, ptr @high.only)
                  %p = call ptr @siyo_rt_str_concat(ptr %h, ptr @low.only)
                  %q = call ptr @siyo_rt_str_concat(ptr %h, ptr @one)
                  call void @show(ptr %h)
                  call void @show(ptr %p)
                  call void @show(ptr %q)
                  call void @same.data(ptr %h, ptr %p)
                  call void @same.data(ptr %h, ptr %q)
                  %self = call ptr @siyo_rt_str_concat(ptr %c, ptr %c)
                  call void @show(ptr %self)
                  call void @show(ptr %c)
                  ret void
                }
                """);
        assertEquals("""
                abab
                4
                4
                abab1
                5
                5
                abab2
                5
                5
                abab13
                6
                6
                abab12
                6
                6
                true
                false
                true
                false
                ab?
                3
                5
                ab\uD83D\uDE00
                4
                6
                ab?1
                4
                6
                false
                true
                abab13abab13
                12
                12
                abab13
                6
                6
                """, stdout);
    }
}
