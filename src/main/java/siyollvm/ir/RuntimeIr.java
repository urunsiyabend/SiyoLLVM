package siyollvm.ir;

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
 * is a pointer to a string or to a {@code SiyoBox} {@code { i32 tag, i64 value }},
 * or null. C {@code _Bool} crosses the boundary as {@code zeroext i1}.
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
                declare ptr @siyo_rt_any_to_string(ptr)
                declare zeroext i1 @siyo_rt_any_equals(ptr, ptr)
                declare void @siyo_rt_print_any(ptr)
                declare void @siyo_rt_div_by_zero() noreturn cold
                declare void @siyo_rt_stack_overflow() noreturn cold

                ; Lowest stack address a Siyo function may start at; set by the runtime.
                @siyo_rt_stack_limit = external global i64
                """;
    }
}
