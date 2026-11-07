package siyollvm.ir;

import java.util.Set;

/**
 * The fixed part of every module: declarations of the C runtime
 * ({@code src/main/resources/siyollvm/runtime/siyo_rt.c}), which clang compiles
 * beside the module. The runtime defines {@code main}, runs the module's
 * {@code siyo_program} on a thread with a 16 MiB stack, and sets
 * {@code siyo_rt_stack_limit} before it does.
 *
 * <p>A Siyo string is a pointer to the runtime's {@code SiyoStr},
 * {@code { i32 tag, i32 utf16 length, i64 byte length, ptr data, ptr buffer }}
 * (the buffer is null for a literal), or null for Siyo's null. An {@code any}
 * is a pointer to a string, to a {@code SiyoBox} {@code { i32 tag, i64 value }}
 * or to an array, or null. An array is a pointer to a {@code SiyoArray}
 * {@code { i32 tag, i32 length, i32 capacity, ptr items }} whose items are
 * {@code any} values, or null. C {@code _Bool} crosses the boundary as {@code zeroext i1}.
 *
 * <p>Exceptions are explicit: a raise sets the thread-local pending flag and
 * returns, and the code after each raising call branches on the flag (no
 * setjmp/longjmp). {@code siyo_rt_catch} clears it and yields what a catch
 * variable receives; {@code siyo_rt_uncaught} reports and exits.
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
                declare ptr @siyo_rt_str_concat(ptr, ptr)
                declare ptr @siyo_rt_str_from_int(i32)
                declare ptr @siyo_rt_str_from_long(i64)
                declare ptr @siyo_rt_str_from_bool(i1 zeroext)
                declare ptr @siyo_rt_str_of_string(ptr)
                declare i32 @siyo_rt_str_len(ptr)
                declare ptr @siyo_rt_str_substring(ptr, i32, i32)
                declare zeroext i1 @siyo_rt_str_contains(ptr, ptr)
                declare i32 @siyo_rt_str_index_of(ptr, ptr)
                declare zeroext i1 @siyo_rt_str_starts_with(ptr, ptr)
                declare zeroext i1 @siyo_rt_str_ends_with(ptr, ptr)
                declare ptr @siyo_rt_str_replace(ptr, ptr, ptr)
                declare ptr @siyo_rt_str_trim(ptr)
                declare ptr @siyo_rt_str_to_upper(ptr)
                declare ptr @siyo_rt_str_to_lower(ptr)
                declare ptr @siyo_rt_chr(i32)
                declare i32 @siyo_rt_ord(ptr)
                declare i32 @siyo_rt_str_compare(ptr, ptr)
                declare i32 @siyo_rt_parse_int(ptr)
                declare i64 @siyo_rt_parse_long(ptr)
                declare ptr @siyo_rt_box_int(i32)
                declare ptr @siyo_rt_box_long(i64)
                declare ptr @siyo_rt_box_bool(i1 zeroext)
                declare i32 @siyo_rt_unbox_int(ptr)
                declare i64 @siyo_rt_unbox_long(ptr)
                declare zeroext i1 @siyo_rt_unbox_bool(ptr)
                declare ptr @siyo_rt_cast_str(ptr)
                declare ptr @siyo_rt_cast_array(ptr)
                declare ptr @siyo_rt_any_to_string(ptr)
                declare zeroext i1 @siyo_rt_any_equals(ptr, ptr)
                declare void @siyo_rt_print_any(ptr)
                declare ptr @siyo_rt_array_new(i32)
                declare void @siyo_rt_array_push(ptr, ptr)
                declare ptr @siyo_rt_array_get(ptr, i32)
                declare ptr @siyo_rt_array_set(ptr, i32, ptr)
                declare ptr @siyo_rt_array_pop(ptr)
                declare void @siyo_rt_array_remove_at(ptr, i32)
                declare i32 @siyo_rt_array_len(ptr)
                declare void @siyo_rt_throw(ptr)
                declare ptr @siyo_rt_catch()
                declare void @siyo_rt_uncaught() noreturn cold
                declare void @siyo_rt_div_by_zero() cold
                declare void @siyo_rt_unbox_null_literal(i32) cold
                declare void @siyo_rt_stack_overflow() noreturn cold

                ; Lowest stack address a Siyo function may start at; set by the runtime.
                @siyo_rt_stack_limit = external global i64
                ; Nonzero (1) while an exception is pending; see RAISING.
                @siyo_rt_pending_flag = external thread_local global i8
                """;
    }

    /**
     * Runtime functions that can raise: they record a pending exception, set
     * {@code @siyo_rt_pending_flag} and return a dummy value, so every call to
     * one is followed by a check of the flag. The others never raise (their
     * failures, such as running out of memory, end the process).
     * {@code siyo_rt_div_by_zero}, {@code siyo_rt_unbox_null_literal} and
     * {@code siyo_rt_throw} always raise; the emitter branches to the handler
     * after them without a check.
     */
    static final Set<String> RAISING = Set.of(
            "@siyo_rt_str_len", "@siyo_rt_str_substring", "@siyo_rt_str_index_of", "@siyo_rt_str_contains",
            "@siyo_rt_str_starts_with", "@siyo_rt_str_ends_with", "@siyo_rt_str_replace", "@siyo_rt_str_trim",
            "@siyo_rt_str_to_upper", "@siyo_rt_str_to_lower", "@siyo_rt_chr", "@siyo_rt_ord",
            "@siyo_rt_str_compare", "@siyo_rt_parse_long",
            "@siyo_rt_unbox_int", "@siyo_rt_unbox_long", "@siyo_rt_unbox_bool", "@siyo_rt_cast_str",
            "@siyo_rt_cast_array", "@siyo_rt_array_push", "@siyo_rt_array_get", "@siyo_rt_array_set",
            "@siyo_rt_array_pop", "@siyo_rt_array_remove_at", "@siyo_rt_array_len");
}
