package siyollvm.ir;

/**
 * The fixed part of every module: declarations of the C runtime
 * ({@code src/main/resources/siyollvm/runtime/siyo_rt.c}), which clang compiles
 * beside the module. The runtime defines {@code main}, runs the module's
 * {@code siyo_program} on a thread with a 16 MiB stack, and sets
 * {@code siyo_rt_stack_limit} before it does.
 *
 * <p>A Siyo string is a pointer to the runtime's {@code SiyoStr},
 * {@code { i32 tag, i32 utf16 length, i64 byte length, [n x i8] bytes }}, or
 * null for Siyo's null. C {@code _Bool} crosses the boundary as
 * {@code zeroext i1}.
 */
final class RuntimeIr {
    private RuntimeIr() {}

    static String text() {
        return """
                declare void @siyo_rt_print_int(i32)
                declare void @siyo_rt_print_long(i64)
                declare void @siyo_rt_print_bool(i1 zeroext)
                declare void @siyo_rt_print_str(ptr)
                declare void @siyo_rt_print_newline()
                declare zeroext i1 @siyo_rt_str_eq(ptr, ptr)
                declare void @siyo_rt_div_by_zero() noreturn cold
                declare void @siyo_rt_stack_overflow() noreturn cold

                ; Lowest stack address a Siyo function may start at; set by the runtime.
                @siyo_rt_stack_limit = external global i64
                """;
    }
}
