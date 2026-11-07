/*
 * SiyoLLVM runtime interface (C11). Compiled by clang beside the program IR,
 * which declares these functions as @siyo_rt_* and defines siyo_program().
 * Uses libc only.
 */
#ifndef SIYO_RT_H
#define SIYO_RT_H

#include <stddef.h>
#include <stdint.h>

/* First word of every heap object. Tags 5-8 are reserved for later features. */
enum {
    SIYO_TAG_STR = 1,
    SIYO_TAG_INT = 2,
    SIYO_TAG_LONG = 3,
    SIYO_TAG_BOOL = 4,
    SIYO_TAG_ARRAY = 5,
    SIYO_TAG_MAP = 6,
    SIYO_TAG_STRUCT = 7,
    SIYO_TAG_EXCEPTION = 8
};

/*
 * The bytes behind runtime strings: capacity, how many bytes are in use, and
 * the bytes themselves. Bytes below the used mark never change, so several
 * headers can share one buffer; only the header ending exactly at the used
 * mark may append past it (see siyo_rt_str_concat).
 */
typedef struct SiyoBuf {
    int64_t cap;
    int64_t used;
    char bytes[];
} SiyoBuf;

/*
 * An immutable Siyo string: a header pointing at WTF-8 bytes (UTF-8 that may
 * hold lone surrogates), not NUL-terminated. utf16_len is the exact length in
 * UTF-16 code units, always recorded when the header is made (by the IR for a
 * literal, by the runtime otherwise) and never written afterwards. buf is the buffer data lies in, or NULL
 * for a literal, whose bytes are a constant of their own. Siyo's null string
 * is a NULL pointer. The IR emits literals as
 * { i32 1, i32 <utf16 length>, i64 <byte length>, ptr <bytes>, ptr null }.
 */
typedef struct SiyoStr {
    uint32_t tag;
    int32_t utf16_len;
    int64_t len;
    const char *data;
    struct SiyoBuf *buf;
} SiyoStr;

_Static_assert(offsetof(SiyoStr, utf16_len) == 4, "SiyoStr layout must match the IR");
_Static_assert(offsetof(SiyoStr, len) == 8, "SiyoStr layout must match the IR");
_Static_assert(offsetof(SiyoStr, data) == 16, "SiyoStr layout must match the IR");
_Static_assert(offsetof(SiyoStr, buf) == 24, "SiyoStr layout must match the IR");
_Static_assert(sizeof(SiyoStr) == 32, "SiyoStr layout must match the IR");

/*
 * A boxed int, long or bool (tag SIYO_TAG_INT, _LONG or _BOOL): what a
 * primitive becomes where the static type is any, as java.lang.Integer, Long
 * and Boolean on the JVM. A bool's value is 0 or 1.
 */
typedef struct SiyoBox {
    uint32_t tag;
    int64_t value;
} SiyoBox;

_Static_assert(offsetof(SiyoBox, value) == 8, "SiyoBox layout");

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

/*
 * a + b, as Java's StringBuilder appends: a null operand reads as "null". A
 * lone high surrogate ending a and a lone low surrogate starting b become one
 * 4-byte sequence, as their UTF-16 code units would form one code point.
 * Always returns a new header. When a ends at its buffer's used mark, no
 * surrogates join and b fits, b's bytes are appended there in place;
 * otherwise both are copied into a fresh buffer of twice the total size, so
 * repeated appends are amortised linear.
 */
SiyoStr *siyo_rt_str_concat(const SiyoStr *a, const SiyoStr *b);
/* String.valueOf: decimal for int and long, "true"/"false" for bool. */
SiyoStr *siyo_rt_str_from_int(int32_t value);
SiyoStr *siyo_rt_str_from_long(int64_t value);
SiyoStr *siyo_rt_str_from_bool(_Bool value);
/* toString(string): s itself, or "null" for null. */
SiyoStr *siyo_rt_str_of_string(const SiyoStr *s);

/*
 * String builtins. Indexes and lengths count UTF-16 code units, as Java's do;
 * a null string argument raises java.lang.NullPointerException unless noted.
 * Every result is in canonical form: a high surrogate followed by a low one
 * is always stored as one 4-byte sequence.
 */
int32_t siyo_rt_str_len(const SiyoStr *s);
/* String.substring(begin, end); out of range is StringIndexOutOfBoundsException. */
SiyoStr *siyo_rt_str_substring(const SiyoStr *s, int32_t begin, int32_t end);
int32_t siyo_rt_str_index_of(const SiyoStr *s, const SiyoStr *sub);
_Bool siyo_rt_str_contains(const SiyoStr *s, const SiyoStr *sub);
_Bool siyo_rt_str_starts_with(const SiyoStr *s, const SiyoStr *prefix);
_Bool siyo_rt_str_ends_with(const SiyoStr *s, const SiyoStr *suffix);
/* String.replace(CharSequence, CharSequence): literal, every occurrence. */
SiyoStr *siyo_rt_str_replace(const SiyoStr *s, const SiyoStr *target, const SiyoStr *replacement);
/* String.trim: strips units up to U+0020 at both ends. */
SiyoStr *siyo_rt_str_trim(const SiyoStr *s);
/* Per code point in the C.UTF-8 locale, plus Java's ß -> SS and İ -> i̇. */
SiyoStr *siyo_rt_str_to_upper(const SiyoStr *s);
SiyoStr *siyo_rt_str_to_lower(const SiyoStr *s);
/* Character.toString(int): a code point outside 0..0x10FFFF is IllegalArgumentException. */
SiyoStr *siyo_rt_chr(int32_t code_point);
/* charAt(0); empty is StringIndexOutOfBoundsException. */
int32_t siyo_rt_ord(const SiyoStr *s);
/* compareTo over UTF-16 code units: negative, zero or positive. */
int32_t siyo_rt_str_compare(const SiyoStr *a, const SiyoStr *b);
/* Integer.parseInt, 0 for anything it rejects (null included). */
int32_t siyo_rt_parse_int(const SiyoStr *s);
/* Long.parseLong; rejected input, null included, is NumberFormatException. */
int64_t siyo_rt_parse_long(const SiyoStr *s);

/*
 * any values. An any is a pointer to a tagged heap object (a SiyoStr or a
 * SiyoBox) or NULL for null. Boxing allocates; values are never shared or
 * mutated, so identity is never observable (== is Objects.equals).
 */
void *siyo_rt_box_int(int32_t value);
void *siyo_rt_box_long(int64_t value);
void *siyo_rt_box_bool(_Bool value);
/*
 * Checked unboxing, as the JVM backend's checkcast + intValue(): a value of
 * another type raises java.lang.ClassCastException with HotSpot's message,
 * null raises java.lang.NullPointerException. unbox_long also takes a boxed
 * int (SiyoRuntime.unboxLong widens it).
 */
int32_t siyo_rt_unbox_int(const void *value);
int64_t siyo_rt_unbox_long(const void *value);
_Bool siyo_rt_unbox_bool(const void *value);
/* checkcast java/lang/String: the string itself, null passes. */
const SiyoStr *siyo_rt_cast_str(const void *value);
/* String.valueOf(Object): "null" for null, a string itself. */
SiyoStr *siyo_rt_any_to_string(const void *value);
/* Objects.equals: same type and value, so a boxed 5L is not equal to a boxed 5. */
_Bool siyo_rt_any_equals(const void *a, const void *b);
/* PrintStream.print(Object). */
void siyo_rt_print_any(const void *value);

/* Uncaught Java errors: flush stdout, print the exception line to stderr, exit 1. */
_Noreturn void siyo_rt_div_by_zero(void);
_Noreturn void siyo_rt_stack_overflow(void);
/* Prints "<java_class>: <message>" or, for a null message, "<java_class>". */
_Noreturn void siyo_rt_raise_java(const char *java_class, const SiyoStr *message);

/* malloc that never returns NULL: failure is java.lang.OutOfMemoryError. Never freed. */
void *siyo_rt_alloc(size_t size);

#endif
