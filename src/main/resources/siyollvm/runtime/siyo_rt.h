/*
 * SiyoLLVM runtime interface (C11). Compiled by clang beside the program IR,
 * which declares these functions as @siyo_rt_* and defines siyo_program().
 * Uses libc only.
 */
#ifndef SIYO_RT_H
#define SIYO_RT_H

#include <stddef.h>
#include <stdint.h>

/* First word of every heap object. Tag 8 is reserved for a later feature. */
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

/*
 * A Siyo array (tag SIYO_TAG_ARRAY), as the JVM's SiyoArray over an
 * ArrayList<Object>: len items, each an any value (a primitive boxed), in a
 * block of cap slots that doubles when full. Arrays are shared by
 * reference and mutated in place; they are never freed.
 */
typedef struct SiyoArray {
    uint32_t tag;
    int32_t len;
    int32_t cap;
    void **items;
} SiyoArray;

_Static_assert(offsetof(SiyoArray, len) == 4, "SiyoArray layout must match the IR");
_Static_assert(offsetof(SiyoArray, cap) == 8, "SiyoArray layout must match the IR");
_Static_assert(offsetof(SiyoArray, items) == 16, "SiyoArray layout must match the IR");

/*
 * A Siyo struct value (tag SIYO_TAG_STRUCT), as the JVM's SiyoObject, a
 * LinkedHashMap<String, Object> carrying the struct's name: count fields,
 * names[i] holding values[i] (an any value, a primitive boxed), in the order
 * the literal wrote them; a field set later is appended. Structs are shared
 * by reference and mutated in place; they are never freed. names starts out
 * as the literal's constant array and is copied before it grows.
 */
typedef struct SiyoStruct {
    uint32_t tag;
    int32_t count;
    const SiyoStr *type_name;
    const SiyoStr **names;
    void **values;
} SiyoStruct;

_Static_assert(offsetof(SiyoStruct, count) == 4, "SiyoStruct layout must match the IR");
_Static_assert(offsetof(SiyoStruct, type_name) == 8, "SiyoStruct layout must match the IR");
_Static_assert(offsetof(SiyoStruct, names) == 16, "SiyoStruct layout must match the IR");
_Static_assert(offsetof(SiyoStruct, values) == 24, "SiyoStruct layout must match the IR");

/*
 * A Siyo map (tag SIYO_TAG_MAP), as the JVM's SiyoMap over a LinkedHashMap:
 * entries in insertion order (an overwritten key keeps its place; a removed
 * one leaves a dead entry until the next rebuild, and inserting it again
 * appends it), each an any key and value, found through an open-addressing
 * index of entry numbers. Keys are compared by siyo_rt_any_equals and hashed
 * consistently with it. Maps are shared by reference and mutated in place;
 * they are never freed. The layout past the tag is private to the runtime.
 */
typedef struct SiyoMapEntry {
    void *key;
    void *value;
    uint32_t hash;
    _Bool live;
} SiyoMapEntry;

typedef struct SiyoMap {
    uint32_t tag;
    int32_t size;        /* live entries */
    int32_t used;        /* entries made, dead ones included */
    int32_t cap;         /* room in entries */
    SiyoMapEntry *entries;
    int32_t *slots;      /* slot_count entry numbers, -1 for an empty slot */
    int32_t slot_count;  /* a power of two, at least twice cap */
} SiyoMap;

_Static_assert(offsetof(SiyoMap, tag) == 0, "SiyoMap tag must be the first word");

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
/* s[i], String.charAt as a one-unit string (half of a pair is a lone
   surrogate); out of range is StringIndexOutOfBoundsException:
   "Index i out of bounds for length n". */
SiyoStr *siyo_rt_str_char_at(const SiyoStr *s, int32_t index);
/*
 * split(s, delim): s.split(Pattern.quote(delim), -1), a literal split keeping
 * trailing empty strings; no match gives [s] (so "" gives [""]). An empty
 * delimiter splits between every two UTF-16 units and after the last, never
 * before the first. A delimiter holding a surrogate matches by code point, as
 * java.util.regex does: never half of a pair, never from the middle of one.
 */
SiyoArray *siyo_rt_str_split(const SiyoStr *s, const SiyoStr *delim);
/* compareTo over UTF-16 code units: negative, zero or positive. */
int32_t siyo_rt_str_compare(const SiyoStr *a, const SiyoStr *b);
/* Integer.parseInt, 0 for anything it rejects (null included). */
int32_t siyo_rt_parse_int(const SiyoStr *s);
/* Long.parseLong; rejected input, null included, is NumberFormatException. */
int64_t siyo_rt_parse_long(const SiyoStr *s);

/*
 * any values. An any is a pointer to a tagged heap object (a SiyoStr, a
 * SiyoBox, a SiyoArray, a SiyoStruct or a SiyoMap) or NULL for null. Boxes are never mutated, so their
 * identity is never observable (== is Objects.equals): box_int returns one
 * static box per value in -128..127, as Integer.valueOf does, and allocates
 * a new one otherwise.
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
/* checkcast java/util/List: the array itself, null passes. */
SiyoArray *siyo_rt_cast_array(const void *value);
/* checkcast codeanalysis/SiyoMap: the map itself, null passes. */
SiyoMap *siyo_rt_cast_map(const void *value);
/* checkcast java/util/Map: the struct itself, null passes. */
SiyoStruct *siyo_rt_cast_struct(const void *value);
/* checkcast java/util/LinkedHashMap, which the JVM backend uses for an any
   passed to a struct parameter: the struct itself, null passes. */
SiyoStruct *siyo_rt_cast_struct_arg(const void *value);
/* unbox_int and unbox_bool of a value read from a struct field: the same,
   but the NullPointerException's message ends as the JVM's does there,
   'because the return value of "java.util.Map.get(Object)" is null'. */
int32_t siyo_rt_unbox_int_field(const void *value);
_Bool siyo_rt_unbox_bool_field(const void *value);
/* unbox_int and unbox_bool of what SiyoMap.get returned (m[k], or m.get(k)
   where the binder chose get(Object)): the NullPointerException's message
   ends 'because the return value of "codeanalysis.SiyoMap.get(Object)" is
   null'; the _str forms name get(String), the overload the binder picks for
   a string key in m.get(k). */
int32_t siyo_rt_unbox_int_map_get(const void *value);
int32_t siyo_rt_unbox_int_map_get_str(const void *value);
_Bool siyo_rt_unbox_bool_map_get(const void *value);
_Bool siyo_rt_unbox_bool_map_get_str(const void *value);
/*
 * String.valueOf(Object): "null" for null, a string itself, an array as
 * SiyoArray.toString writes it ("[a, b]", each element by String.valueOf,
 * nested arrays recursively), a struct as SiyoObject.toString writes it
 * ("Point { x: 1, y: 2 }", each value by String.valueOf), a map as
 * SiyoMap.toString writes it ("{k: v, k2: w}"). An array, struct or map
 * that contains itself recurses until
 * the stack runs out, which is java.lang.StackOverflowError (fatal, as for
 * Siyo functions).
 */
SiyoStr *siyo_rt_any_to_string(const void *value);
/* Objects.equals: same type and value, so a boxed 5L is not equal to a boxed 5;
   arrays are equal when their elements are, in order (List.equals); structs
   when they have the same field names with equal values, in any order and
   whatever their type names (AbstractMap.equals); maps only when they are the
   same map, as SiyoMap does not override Object.equals. */
_Bool siyo_rt_any_equals(const void *a, const void *b);
/* PrintStream.print(Object). */
void siyo_rt_print_any(const void *value);

/*
 * Arrays. A null array raises java.lang.NullPointerException; an index
 * outside 0..len-1 raises java.lang.IndexOutOfBoundsException:
 * "Index i out of bounds for length n", as ArrayList does.
 */
/* An empty array with room for capacity items. */
SiyoArray *siyo_rt_array_new(int32_t capacity);
/* List.add: appends value, doubling the room when it is full. */
void siyo_rt_array_push(SiyoArray *array, void *value);
/* push onto an array known not to be null (one just made): never raises. */
void siyo_rt_array_append(SiyoArray *array, void *value);
/* range(from, to): a new array of the boxed ints from, from+1, ..., to-1;
   empty when from >= to. Never raises. */
SiyoArray *siyo_rt_range(int32_t from, int32_t to);
/* List.get. */
void *siyo_rt_array_get(SiyoArray *array, int32_t index);
/* List.set: stores value and returns the element it replaced. */
void *siyo_rt_array_set(SiyoArray *array, int32_t index, void *value);
/* List.remove(size() - 1): an empty array is index -1 out of bounds for length 0. */
void *siyo_rt_array_pop(SiyoArray *array);
/* List.remove(index): later elements move down one place. */
void siyo_rt_array_remove_at(SiyoArray *array, int32_t index);
/* List.size. */
int32_t siyo_rt_array_len(SiyoArray *array);

/*
 * Structs. A null struct raises java.lang.NullPointerException. Fields are
 * looked up by name: pointer equality first (the IR interns every name),
 * then the bytes.
 */
/* A struct of the named type with count fields, names[i] holding null; names
   (in the literal's order, distinct) is kept, not copied. Never raises. */
SiyoStruct *siyo_rt_struct_new(const SiyoStr *type_name, int32_t count, const SiyoStr **names);
/* Stores the literal's value of field index into a struct just made. Never raises. */
void siyo_rt_struct_init(SiyoStruct *s, int32_t index, void *value);
/* Map.get: the field's value, null when the struct has no such field. */
void *siyo_rt_struct_get(SiyoStruct *s, const SiyoStr *name);
/* Map.put: replaces the field's value, or appends the field after the others. */
void siyo_rt_struct_set(SiyoStruct *s, const SiyoStr *name, void *value);
/* typeName(value): a struct's type name, "" for anything else (null included). */
SiyoStr *siyo_rt_type_name(const void *value);

/*
 * Maps. A null map raises java.lang.NullPointerException (without the JVM's
 * helpful message), except in len, as SiyoRuntime.lengthOf gives 0 for null.
 */
/* A new, empty map. Never raises. */
SiyoMap *siyo_rt_map_new(void);
/* put into a map known not to be null (one just made): never raises. */
void siyo_rt_map_insert(SiyoMap *map, void *key, void *value);
/* Map.getOrDefault(key, null): the value, null when the key is absent. */
void *siyo_rt_map_get(SiyoMap *map, void *key);
/* Map.put: replaces the value in place, or appends the key after the others. */
void siyo_rt_map_put(SiyoMap *map, void *key, void *value);
/* Map.containsKey. */
_Bool siyo_rt_map_has(SiyoMap *map, void *key);
/* Map.remove: the value removed, or null (SiyoMap.remove itself returns nothing). */
void *siyo_rt_map_remove(SiyoMap *map, void *key);
/* Map.size. */
int32_t siyo_rt_map_size(SiyoMap *map);
/* len(map): its size, 0 for null. Never raises. */
int32_t siyo_rt_map_len(SiyoMap *map);
/* SiyoMap.keys: a new array of the keys in insertion order. */
SiyoArray *siyo_rt_map_keys(SiyoMap *map);

/*
 * Exceptions. A raise records a pending exception and returns; the raising
 * function then returns a dummy value, and the program checks
 * siyo_rt_pending_flag after every call that can raise, branching to the
 * innermost catch or returning to its own caller. No setjmp/longjmp.
 */
extern _Thread_local _Bool siyo_rt_pending_flag;
/* Siyo throw / error(): raises payload (any value, NULL for null) as a SiyoThrow. */
void siyo_rt_throw(void *payload);
/* Raises the Java exception java_class (e.g. "java.lang.ArithmeticException")
   with message, NULL for none. */
void siyo_rt_raise_java(const char *java_class, const SiyoStr *message);
/*
 * Clears the pending exception and returns what a catch variable receives
 * (SiyoRuntime.errorPayload): a SiyoThrow's payload; otherwise the message,
 * or the class's simple name when the message is null or empty.
 */
void *siyo_rt_catch(void);
/* Flushes stdout, prints "codeanalysis.SiyoThrow: <String.valueOf(payload)>"
   or "<java_class>[: <message>]" to stderr and exits with status 1. */
_Noreturn void siyo_rt_uncaught(void);
/* An exception escaping a module's globals or init(): the JVM runs those in
   the module class's initialiser, so the first line it prints is
   "java.lang.ExceptionInInitializerError". Flushes stdout, prints that to
   stderr and exits with status 1. */
_Noreturn void siyo_rt_uncaught_in_initializer(void);
/* Raises java.lang.ArithmeticException: / by zero. */
void siyo_rt_div_by_zero(void);
/* The null literal unboxed to int (tag SIYO_TAG_INT) or bool (SIYO_TAG_BOOL):
   the JVM's NullPointerException, whose message names the literal. */
void siyo_rt_unbox_null_literal(int32_t tag);
/* Fatal and not catchable: prints java.lang.StackOverflowError, exits 1. */
_Noreturn void siyo_rt_stack_overflow(void);

/* malloc that never returns NULL: failure is java.lang.OutOfMemoryError. Never freed. */
void *siyo_rt_alloc(size_t size);

#endif
