/*
 * SiyoLLVM runtime interface (C11). Compiled by clang beside the program IR,
 * which declares these functions as @siyo_rt_* and defines siyo_program().
 * Uses libc only.
 */
#ifndef SIYO_RT_H
#define SIYO_RT_H

#include <stddef.h>
#include <stdint.h>

/* First word of every heap object. Tags 2-8 are reserved for later features. */
enum {
    SIYO_TAG_STR = 1,
    SIYO_TAG_BOXED_INT = 2,
    SIYO_TAG_BOXED_LONG = 3,
    SIYO_TAG_BOXED_BOOL = 4,
    SIYO_TAG_ARRAY = 5,
    SIYO_TAG_MAP = 6,
    SIYO_TAG_STRUCT = 7,
    SIYO_TAG_EXCEPTION = 8
};

/*
 * An immutable Siyo string: WTF-8 bytes (UTF-8 that may hold lone
 * surrogates), not NUL-terminated. utf16_len is the length in UTF-16 code
 * units, or -1 until computed. Siyo's null string is a NULL pointer.
 * The IR emits literals as { i32 1, i32 <utf16 length>, i64 <byte length>, [n x i8] }.
 */
typedef struct SiyoStr {
    uint32_t tag;
    int32_t utf16_len;
    int64_t len;
    char bytes[];
} SiyoStr;

_Static_assert(offsetof(SiyoStr, utf16_len) == 4, "SiyoStr layout must match the IR");
_Static_assert(offsetof(SiyoStr, len) == 8, "SiyoStr layout must match the IR");
_Static_assert(offsetof(SiyoStr, bytes) == 16, "SiyoStr layout must match the IR");

/* Lowest stack address a Siyo function may start at; set on the program thread. */
extern uint64_t siyo_rt_stack_limit;

/* The process arguments, as given to main. */
extern int siyo_rt_argc;
extern char **siyo_rt_argv;

/* Defined by the program IR: globals, then init(), then main(). */
void siyo_program(void);

void siyo_rt_print_int(int32_t value);
void siyo_rt_print_long(int64_t value);
void siyo_rt_print_bool(_Bool value);
/* Null prints "null"; a lone surrogate prints '?', as Java's UTF-8 encoder does. */
void siyo_rt_print_str(const SiyoStr *s);
void siyo_rt_print_newline(void);

/* String.equals, with null equal only to null. */
_Bool siyo_rt_str_eq(const SiyoStr *a, const SiyoStr *b);

/* Uncaught Java errors: flush stdout, print the exception line to stderr, exit 1. */
_Noreturn void siyo_rt_div_by_zero(void);
_Noreturn void siyo_rt_stack_overflow(void);
/* Prints "<java_class>: <message>" or, for a null message, "<java_class>". */
_Noreturn void siyo_rt_raise_java(const char *java_class, const SiyoStr *message);

/* malloc that never returns NULL: failure is java.lang.OutOfMemoryError. Never freed. */
void *siyo_rt_alloc(size_t size);

#endif
