/*
 * SiyoLLVM runtime (C11, libc only). See siyo_rt.h.
 */
#define _GNU_SOURCE

#include "siyo_rt.h"

#include <locale.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wctype.h>

/*
 * The program runs on a thread with a stack of exactly this size, so the
 * guard's budget does not depend on argv, the environment or ulimit -s.
 * The headroom below the limit is left for libc calls.
 */
#define SIYO_RT_STACK_SIZE ((size_t)16 * 1024 * 1024)
#define SIYO_RT_STACK_HEADROOM ((uint64_t)256 * 1024)

uint64_t siyo_rt_stack_limit;
int siyo_rt_argc;
char **siyo_rt_argv;

/* Flushes stdout, writes message to stderr and ends the process with status 1. */
static _Noreturn void siyo_rt_fail(const char *message) {
    fflush(NULL);
    fputs(message, stderr);
    exit(1);
}

/* Writes s's bytes, each lone surrogate (ED A0..BF xx in WTF-8) as '?'. */
static void siyo_rt_write_str(const SiyoStr *s, FILE *out) {
    const unsigned char *bytes = (const unsigned char *)s->data;
    size_t len = (size_t)s->len;
    size_t start = 0;
    for (size_t i = 0; i + 2 < len; i++) {
        if (bytes[i] == 0xED && bytes[i + 1] >= 0xA0) {
            fwrite(bytes + start, 1, i - start, out);
            fputc('?', out);
            i += 2;
            start = i + 1;
        }
    }
    fwrite(bytes + start, 1, len - start, out);
}

void siyo_rt_print_int(int32_t value) {
    printf("%d", (int)value);
}

void siyo_rt_print_long(int64_t value) {
    printf("%lld", (long long)value);
}

void siyo_rt_print_bool(_Bool value) {
    fputs(value ? "true" : "false", stdout);
}

void siyo_rt_print_str(const SiyoStr *s) {
    if (s == NULL) {
        fputs("null", stdout);
        return;
    }
    siyo_rt_write_str(s, stdout);
}

void siyo_rt_print_newline(void) {
    fputc('\n', stdout);
}

_Bool siyo_rt_str_eq(const SiyoStr *a, const SiyoStr *b) {
    if (a == b) return 1;
    if (a == NULL || b == NULL) return 0;
    if (a->len != b->len) return 0;
    return memcmp(a->data, b->data, (size_t)a->len) == 0;
}

/* A new header over len bytes at data in buf. Java strings hold fewer than
   2^31 UTF-16 code units; a longer one is an OutOfMemoryError. */
static SiyoStr *siyo_rt_str_header(const char *data, int64_t len, int64_t utf16_len, SiyoBuf *buf) {
    if (utf16_len > INT32_MAX) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    SiyoStr *s = siyo_rt_alloc(sizeof(SiyoStr));
    s->tag = SIYO_TAG_STR;
    s->utf16_len = (int32_t)utf16_len;
    s->len = len;
    s->data = data;
    s->buf = buf;
    return s;
}

/* An empty buffer with room for cap bytes. */
static SiyoBuf *siyo_rt_buf_new(int64_t cap) {
    if (cap < 0 || (uint64_t)cap > SIZE_MAX - sizeof(SiyoBuf)) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    SiyoBuf *buf = siyo_rt_alloc(sizeof(SiyoBuf) + (size_t)cap);
    buf->cap = cap;
    buf->used = 0;
    return buf;
}

/* A new string holding a copy of len bytes, in a buffer of exactly that size. */
static SiyoStr *siyo_rt_str_from_bytes(const char *bytes, int64_t len, int64_t utf16_len) {
    if (utf16_len > INT32_MAX) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    SiyoBuf *buf = siyo_rt_buf_new(len);
    memcpy(buf->bytes, bytes, (size_t)len);
    buf->used = len;
    return siyo_rt_str_header(buf->bytes, len, utf16_len, buf);
}

/* UTF-16 units in len bytes of WTF-8: one per sequence, two for a 4-byte one. */
static int64_t siyo_rt_count_units(const unsigned char *bytes, int64_t len) {
    int64_t units = 0;
    for (int64_t i = 0; i < len; i++) {
        if ((bytes[i] & 0xC0) != 0x80) units += bytes[i] >= 0xF0 ? 2 : 1;
    }
    return units;
}

/* UTF-16 length of s. */
static int64_t siyo_rt_str_utf16_len(const SiyoStr *s) {
    if (s->utf16_len >= 0) return s->utf16_len;
    return siyo_rt_count_units((const unsigned char *)s->data, s->len);
}

/* The surrogate a 3-byte WTF-8 sequence at p encodes, or 0 if it is none. */
static unsigned siyo_rt_surrogate_at(const unsigned char *p) {
    if (p[0] != 0xED || p[1] < 0xA0) return 0;
    return 0xD000u | ((p[1] & 0x3Fu) << 6) | (p[2] & 0x3Fu);
}

SiyoStr *siyo_rt_str_concat(const SiyoStr *a, const SiyoStr *b) {
    static const char null_text[] = "null";
    const unsigned char *ab = a != NULL ? (const unsigned char *)a->data : (const unsigned char *)null_text;
    const unsigned char *bb = b != NULL ? (const unsigned char *)b->data : (const unsigned char *)null_text;
    int64_t alen = a != NULL ? a->len : 4;
    int64_t blen = b != NULL ? b->len : 4;
    int64_t units = (a != NULL ? siyo_rt_str_utf16_len(a) : 4) + (b != NULL ? siyo_rt_str_utf16_len(b) : 4);

    unsigned high = alen >= 3 ? siyo_rt_surrogate_at(ab + alen - 3) : 0;
    unsigned low = blen >= 3 ? siyo_rt_surrogate_at(bb) : 0;
    _Bool joins = high >= 0xD800 && high <= 0xDBFF && low >= 0xDC00 && low <= 0xDFFF;

    /* In place: a ends at its buffer's used mark, so no other header sees the
       bytes past it. b may lie in the same buffer, but only below the mark. */
    SiyoBuf *abuf = a != NULL ? a->buf : NULL;
    if (!joins && abuf != NULL && a->data + alen == abuf->bytes + abuf->used && blen <= abuf->cap - abuf->used) {
        SiyoStr *s = siyo_rt_str_header(a->data, alen + blen, units, abuf);
        memcpy(abuf->bytes + abuf->used, bb, (size_t)blen);
        abuf->used += blen;
        return s;
    }

    /* A fresh buffer, with room to append as much again. The joined pair is
       still two UTF-16 units; it takes 4 bytes instead of 6. */
    int64_t len = joins ? alen + blen - 2 : alen + blen;
    if (units > INT32_MAX) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    SiyoBuf *buf = siyo_rt_buf_new(len < 16 ? 32 : 2 * len);
    unsigned char *out = (unsigned char *)buf->bytes;
    if (joins) {
        uint32_t cp = 0x10000u + ((high - 0xD800u) << 10) + (low - 0xDC00u);
        memcpy(out, ab, (size_t)(alen - 3));
        out += alen - 3;
        out[0] = (unsigned char)(0xF0 | (cp >> 18));
        out[1] = (unsigned char)(0x80 | ((cp >> 12) & 0x3F));
        out[2] = (unsigned char)(0x80 | ((cp >> 6) & 0x3F));
        out[3] = (unsigned char)(0x80 | (cp & 0x3F));
        memcpy(out + 4, bb + 3, (size_t)(blen - 3));
    } else {
        memcpy(out, ab, (size_t)alen);
        memcpy(out + alen, bb, (size_t)blen);
    }
    buf->used = len;
    return siyo_rt_str_header(buf->bytes, len, units, buf);
}

SiyoStr *siyo_rt_str_from_int(int32_t value) {
    return siyo_rt_str_from_long(value);
}

SiyoStr *siyo_rt_str_from_long(int64_t value) {
    char buffer[24];
    int len = snprintf(buffer, sizeof buffer, "%lld", (long long)value);
    return siyo_rt_str_from_bytes(buffer, len, len);
}

SiyoStr *siyo_rt_str_from_bool(_Bool value) {
    return value ? siyo_rt_str_from_bytes("true", 4, 4) : siyo_rt_str_from_bytes("false", 5, 5);
}

SiyoStr *siyo_rt_str_of_string(const SiyoStr *s) {
    if (s == NULL) return siyo_rt_str_from_bytes("null", 4, 4);
    return (SiyoStr *)s;
}

/* --- string builtins ------------------------------------------------------ */

/* Raising is fatal in F1; callers still return a dummy value afterwards, so
   they stay correct once a raise can be caught. */
static void siyo_rt_null_pointer(void) {
    siyo_rt_raise_java("java.lang.NullPointerException", NULL);
}

/* Raises java_class with a short ASCII message formatted from format. */
__attribute__((format(printf, 2, 3)))
static void siyo_rt_raise_format(const char *java_class, const char *format, ...) {
    char text[128];
    va_list args;
    va_start(args, format);
    int len = vsnprintf(text, sizeof text, format, args);
    va_end(args);
    if (len < 0) len = 0;
    if (len >= (int)sizeof text) len = (int)sizeof text - 1;
    siyo_rt_raise_java(java_class, siyo_rt_str_from_bytes(text, len, len));
}

/* Length of the WTF-8 sequence starting with lead. */
static int siyo_rt_seq_len(unsigned char lead) {
    return lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
}

/* Decodes the sequence at p into *cp (a lone surrogate as its own value); returns its length. */
static int siyo_rt_decode(const unsigned char *p, uint32_t *cp) {
    switch (siyo_rt_seq_len(p[0])) {
    case 1: *cp = p[0]; return 1;
    case 2: *cp = ((p[0] & 0x1Fu) << 6) | (p[1] & 0x3Fu); return 2;
    case 3: *cp = ((p[0] & 0x0Fu) << 12) | ((p[1] & 0x3Fu) << 6) | (p[2] & 0x3Fu); return 3;
    default:
        *cp = ((p[0] & 0x07u) << 18) | ((p[1] & 0x3Fu) << 12) | ((p[2] & 0x3Fu) << 6) | (p[3] & 0x3Fu);
        return 4;
    }
}

static int siyo_rt_encoded_len(uint32_t cp) {
    return cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
}

/* Writes cp at out, a surrogate as a lone 3-byte sequence; returns the length. */
static int siyo_rt_encode(uint32_t cp, unsigned char *out) {
    if (cp < 0x80) {
        out[0] = (unsigned char)cp;
        return 1;
    }
    if (cp < 0x800) {
        out[0] = (unsigned char)(0xC0 | (cp >> 6));
        out[1] = (unsigned char)(0x80 | (cp & 0x3F));
        return 2;
    }
    if (cp < 0x10000) {
        out[0] = (unsigned char)(0xE0 | (cp >> 12));
        out[1] = (unsigned char)(0x80 | ((cp >> 6) & 0x3F));
        out[2] = (unsigned char)(0x80 | (cp & 0x3F));
        return 3;
    }
    out[0] = (unsigned char)(0xF0 | (cp >> 18));
    out[1] = (unsigned char)(0x80 | ((cp >> 12) & 0x3F));
    out[2] = (unsigned char)(0x80 | ((cp >> 6) & 0x3F));
    out[3] = (unsigned char)(0x80 | (cp & 0x3F));
    return 4;
}

static uint32_t siyo_rt_high_of(uint32_t cp) {
    return 0xD800u + ((cp - 0x10000u) >> 10);
}

static uint32_t siyo_rt_low_of(uint32_t cp) {
    return 0xDC00u + ((cp - 0x10000u) & 0x3FFu);
}

/* Reads a string's UTF-16 code units one at a time. */
typedef struct SiyoUnits {
    const unsigned char *p;
    const unsigned char *end;
    int32_t low; /* the second half of a pair still to come, or -1 */
} SiyoUnits;

static SiyoUnits siyo_rt_units_from(const SiyoStr *s, int64_t offset) {
    SiyoUnits it = { (const unsigned char *)s->data + offset, (const unsigned char *)s->data + s->len, -1 };
    return it;
}

/* The next unit, or -1 at the end. */
static int32_t siyo_rt_next_unit(SiyoUnits *it) {
    if (it->low >= 0) {
        int32_t unit = it->low;
        it->low = -1;
        return unit;
    }
    if (it->p == it->end) return -1;
    uint32_t cp;
    it->p += siyo_rt_decode(it->p, &cp);
    if (cp < 0x10000) return (int32_t)cp;
    it->low = (int32_t)siyo_rt_low_of(cp);
    return (int32_t)siyo_rt_high_of(cp);
}

/* s's UTF-16 units in a temporary array the caller frees; *count gets their number. */
static uint16_t *siyo_rt_to_units(const SiyoStr *s, int64_t *count) {
    int64_t n = siyo_rt_str_utf16_len(s);
    uint16_t *units = siyo_rt_alloc((size_t)n * sizeof(uint16_t));
    SiyoUnits it = siyo_rt_units_from(s, 0);
    for (int64_t i = 0; i < n; i++) units[i] = (uint16_t)siyo_rt_next_unit(&it);
    *count = n;
    return units;
}

static _Bool siyo_rt_pair_at(const uint16_t *units, int64_t i, int64_t n) {
    return units[i] >= 0xD800 && units[i] <= 0xDBFF && i + 1 < n && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF;
}

/* A new string of n UTF-16 units, each surrogate pair as one 4-byte sequence. */
static SiyoStr *siyo_rt_str_from_units(const uint16_t *units, int64_t n) {
    if (n > INT32_MAX) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    int64_t len = 0;
    for (int64_t i = 0; i < n; i++) {
        if (siyo_rt_pair_at(units, i, n)) {
            len += 4;
            i++;
        } else {
            len += siyo_rt_encoded_len(units[i]);
        }
    }
    SiyoBuf *buf = siyo_rt_buf_new(len);
    unsigned char *out = (unsigned char *)buf->bytes;
    for (int64_t i = 0; i < n; i++) {
        uint32_t cp = units[i];
        if (siyo_rt_pair_at(units, i, n)) {
            cp = 0x10000u + ((cp - 0xD800u) << 10) + (units[i + 1] - 0xDC00u);
            i++;
        }
        out += siyo_rt_encode(cp, out);
    }
    buf->used = len;
    return siyo_rt_str_header(buf->bytes, len, n, buf);
}

/* First index at or after from where needle's m units occur in hay's n, or -1. */
static int64_t siyo_rt_find_units(const uint16_t *hay, int64_t n, const uint16_t *needle, int64_t m, int64_t from) {
    for (int64_t i = from; i + m <= n; i++) {
        if (memcmp(hay + i, needle, (size_t)m * sizeof(uint16_t)) == 0) return i;
    }
    return -1;
}

/* Whether t starts or ends with a lone surrogate. Such a t can match half of
   a pair, which byte comparison misses, so it is compared unit by unit. */
static _Bool siyo_rt_edge_surrogate(const SiyoStr *t) {
    const unsigned char *b = (const unsigned char *)t->data;
    return t->len >= 3 && (siyo_rt_surrogate_at(b) != 0 || siyo_rt_surrogate_at(b + t->len - 3) != 0);
}

/* Whether t's units are s's first (at_end false) or last ones. */
static _Bool siyo_rt_units_match(const SiyoStr *s, const SiyoStr *t, _Bool at_end) {
    int64_t n, m;
    uint16_t *a = siyo_rt_to_units(s, &n);
    uint16_t *b = siyo_rt_to_units(t, &m);
    _Bool match = m <= n && memcmp(a + (at_end ? n - m : 0), b, (size_t)m * sizeof(uint16_t)) == 0;
    free(a);
    free(b);
    return match;
}

/* Byte offset of UTF-16 index unit (0..length) in s. When unit falls between
   the halves of a 4-byte sequence, *split is set and that sequence's start
   is returned. */
static int64_t siyo_rt_offset_of(const SiyoStr *s, int64_t unit, _Bool *split) {
    *split = 0;
    if (s->len == siyo_rt_str_utf16_len(s)) return unit; /* ASCII */
    const unsigned char *b = (const unsigned char *)s->data;
    int64_t i = 0;
    for (int64_t u = 0; u < unit;) {
        int n = siyo_rt_seq_len(b[i]);
        if (n == 4) {
            if (u + 1 == unit) {
                *split = 1;
                return i;
            }
            u += 2;
        } else {
            u += 1;
        }
        i += n;
    }
    return i;
}

int32_t siyo_rt_str_len(const SiyoStr *s) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    return (int32_t)siyo_rt_str_utf16_len(s);
}

SiyoStr *siyo_rt_str_substring(const SiyoStr *s, int32_t begin, int32_t end) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    int64_t length = siyo_rt_str_utf16_len(s);
    if (begin < 0 || begin > end || end > length) {
        siyo_rt_raise_format("java.lang.StringIndexOutOfBoundsException",
                "Range [%d, %d) out of bounds for length %lld", (int)begin, (int)end, (long long)length);
        return NULL;
    }
    if (begin == end) return siyo_rt_str_from_bytes("", 0, 0);
    _Bool begin_split, end_split;
    int64_t from = siyo_rt_offset_of(s, begin, &begin_split);
    int64_t to = siyo_rt_offset_of(s, end, &end_split);
    if (!begin_split && !end_split) {
        /* Shares s's bytes; appending to it later copies unless it ends at the used mark. */
        return siyo_rt_str_header(s->data + from, to - from, end - begin, s->buf);
    }
    /* A split pair contributes its low half at the start or its high half at the end. */
    const unsigned char *b = (const unsigned char *)s->data;
    int64_t middle = begin_split ? from + 4 : from;
    int64_t len = (begin_split ? 3 : 0) + (to - middle) + (end_split ? 3 : 0);
    SiyoBuf *buf = siyo_rt_buf_new(len);
    unsigned char *out = (unsigned char *)buf->bytes;
    uint32_t cp;
    if (begin_split) {
        siyo_rt_decode(b + from, &cp);
        out += siyo_rt_encode(siyo_rt_low_of(cp), out);
    }
    memcpy(out, b + middle, (size_t)(to - middle));
    out += to - middle;
    if (end_split) {
        siyo_rt_decode(b + to, &cp);
        siyo_rt_encode(siyo_rt_high_of(cp), out);
    }
    buf->used = len;
    return siyo_rt_str_header(buf->bytes, len, end - begin, buf);
}

int32_t siyo_rt_str_index_of(const SiyoStr *s, const SiyoStr *sub) {
    if (s == NULL || sub == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    if (siyo_rt_edge_surrogate(sub)) {
        int64_t n, m;
        uint16_t *hay = siyo_rt_to_units(s, &n);
        uint16_t *needle = siyo_rt_to_units(sub, &m);
        int64_t at = siyo_rt_find_units(hay, n, needle, m, 0);
        free(hay);
        free(needle);
        return (int32_t)at;
    }
    /* sub starts with a lead byte and ends a sequence, so a byte match is a unit match. */
    if (sub->len == 0) return 0;
    const char *found = memmem(s->data, (size_t)s->len, sub->data, (size_t)sub->len);
    if (found == NULL) return -1;
    return (int32_t)siyo_rt_count_units((const unsigned char *)s->data, found - s->data);
}

_Bool siyo_rt_str_contains(const SiyoStr *s, const SiyoStr *sub) {
    return siyo_rt_str_index_of(s, sub) >= 0;
}

_Bool siyo_rt_str_starts_with(const SiyoStr *s, const SiyoStr *prefix) {
    if (s == NULL || prefix == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    if (siyo_rt_edge_surrogate(prefix)) return siyo_rt_units_match(s, prefix, 0);
    return prefix->len <= s->len && memcmp(s->data, prefix->data, (size_t)prefix->len) == 0;
}

_Bool siyo_rt_str_ends_with(const SiyoStr *s, const SiyoStr *suffix) {
    if (s == NULL || suffix == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    if (siyo_rt_edge_surrogate(suffix)) return siyo_rt_units_match(s, suffix, 1);
    return suffix->len <= s->len
            && memcmp(s->data + (s->len - suffix->len), suffix->data, (size_t)suffix->len) == 0;
}

/* Works on UTF-16 units throughout, so a target may match half of a pair and
   halves brought together by a removal join, as in Java. */
SiyoStr *siyo_rt_str_replace(const SiyoStr *s, const SiyoStr *target, const SiyoStr *replacement) {
    if (s == NULL || target == NULL || replacement == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    int64_t n, m, r;
    uint16_t *hay = siyo_rt_to_units(s, &n);
    uint16_t *find = siyo_rt_to_units(target, &m);
    int64_t matches = 0;
    if (m == 0) {
        matches = n + 1; /* before every unit and at the end */
    } else {
        for (int64_t at = siyo_rt_find_units(hay, n, find, m, 0); at >= 0;
                at = siyo_rt_find_units(hay, n, find, m, at + m)) {
            matches++;
        }
    }
    if (matches == 0) {
        free(hay);
        free(find);
        return (SiyoStr *)s;
    }
    uint16_t *with = siyo_rt_to_units(replacement, &r);
    int64_t total = n + matches * (r - m);
    if (total > INT32_MAX) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    uint16_t *out = siyo_rt_alloc((size_t)total * sizeof(uint16_t));
    int64_t k = 0;
    if (m == 0) {
        for (int64_t i = 0; i <= n; i++) {
            memcpy(out + k, with, (size_t)r * sizeof(uint16_t));
            k += r;
            if (i < n) out[k++] = hay[i];
        }
    } else {
        int64_t from = 0;
        for (int64_t at = siyo_rt_find_units(hay, n, find, m, 0); at >= 0;
                at = siyo_rt_find_units(hay, n, find, m, at + m)) {
            memcpy(out + k, hay + from, (size_t)(at - from) * sizeof(uint16_t));
            k += at - from;
            memcpy(out + k, with, (size_t)r * sizeof(uint16_t));
            k += r;
            from = at + m;
        }
        memcpy(out + k, hay + from, (size_t)(n - from) * sizeof(uint16_t));
    }
    SiyoStr *result = siyo_rt_str_from_units(out, total);
    free(hay);
    free(find);
    free(with);
    free(out);
    return result;
}

SiyoStr *siyo_rt_str_trim(const SiyoStr *s) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    /* Units up to U+0020 are single bytes; no other byte is that small. */
    const unsigned char *b = (const unsigned char *)s->data;
    int64_t start = 0;
    int64_t end = s->len;
    while (start < end && b[start] <= 0x20) start++;
    while (end > start && b[end - 1] <= 0x20) end--;
    if (start == 0 && end == s->len) return (SiyoStr *)s;
    int64_t units = siyo_rt_str_utf16_len(s) - (s->len - (end - start));
    return siyo_rt_str_header(s->data + start, end - start, units, s->buf);
}

/* The C.UTF-8 locale for case mapping, made once and kept; (locale_t)0 if unavailable. */
static locale_t siyo_rt_case_locale(void) {
    static locale_t locale;
    static _Bool made;
    if (!made) {
        made = 1;
        locale = newlocale(LC_CTYPE_MASK, "C.UTF-8", (locale_t)0);
    }
    return locale;
}

static uint32_t siyo_rt_map_case(uint32_t cp, _Bool upper) {
    if (cp >= 0xD800 && cp <= 0xDFFF) return cp;
    locale_t locale = siyo_rt_case_locale();
    if (locale == (locale_t)0) {
        /* Without the locale only ASCII letters change. */
        if (upper && cp >= 'a' && cp <= 'z') return cp - 32;
        if (!upper && cp >= 'A' && cp <= 'Z') return cp + 32;
        return cp;
    }
    return (uint32_t)(upper ? towupper_l((wint_t)cp, locale) : towlower_l((wint_t)cp, locale));
}

/* Measures (out NULL) or writes s mapped to upper or lower case; returns the byte length. */
static int64_t siyo_rt_case_pass(const SiyoStr *s, _Bool upper, unsigned char *out, int64_t *units) {
    const unsigned char *b = (const unsigned char *)s->data;
    unsigned char scratch[4];
    int64_t len = 0;
    *units = 0;
    for (int64_t i = 0; i < s->len;) {
        uint32_t cp;
        i += siyo_rt_decode(b + i, &cp);
        uint32_t mapped[2];
        int count = 1;
        if (upper && cp == 0xDF) { /* ß -> SS */
            mapped[0] = 'S';
            mapped[1] = 'S';
            count = 2;
        } else if (!upper && cp == 0x130) { /* İ -> i + COMBINING DOT ABOVE */
            mapped[0] = 'i';
            mapped[1] = 0x307;
            count = 2;
        } else {
            mapped[0] = siyo_rt_map_case(cp, upper);
        }
        for (int j = 0; j < count; j++) {
            len += siyo_rt_encode(mapped[j], out != NULL ? out + len : scratch);
            *units += mapped[j] >= 0x10000 ? 2 : 1;
        }
    }
    return len;
}

static SiyoStr *siyo_rt_str_case(const SiyoStr *s, _Bool upper) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    int64_t units;
    int64_t len = siyo_rt_case_pass(s, upper, NULL, &units);
    SiyoBuf *buf = siyo_rt_buf_new(len);
    siyo_rt_case_pass(s, upper, (unsigned char *)buf->bytes, &units);
    buf->used = len;
    return siyo_rt_str_header(buf->bytes, len, units, buf);
}

SiyoStr *siyo_rt_str_to_upper(const SiyoStr *s) {
    return siyo_rt_str_case(s, 1);
}

SiyoStr *siyo_rt_str_to_lower(const SiyoStr *s) {
    return siyo_rt_str_case(s, 0);
}

SiyoStr *siyo_rt_chr(int32_t code_point) {
    if (code_point < 0 || code_point > 0x10FFFF) {
        siyo_rt_raise_format("java.lang.IllegalArgumentException", "Not a valid Unicode code point: 0x%X",
                (unsigned)code_point);
        return NULL;
    }
    unsigned char bytes[4];
    int len = siyo_rt_encode((uint32_t)code_point, bytes);
    return siyo_rt_str_from_bytes((const char *)bytes, len, code_point >= 0x10000 ? 2 : 1);
}

int32_t siyo_rt_ord(const SiyoStr *s) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    if (s->len == 0) {
        siyo_rt_raise_format("java.lang.StringIndexOutOfBoundsException", "Index 0 out of bounds for length 0");
        return 0;
    }
    SiyoUnits it = siyo_rt_units_from(s, 0);
    return siyo_rt_next_unit(&it);
}

int32_t siyo_rt_str_compare(const SiyoStr *a, const SiyoStr *b) {
    if (a == NULL || b == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    /* Skip the bytes both share, back to the start of the sequence that differs. */
    const unsigned char *x = (const unsigned char *)a->data;
    const unsigned char *y = (const unsigned char *)b->data;
    int64_t shared = a->len < b->len ? a->len : b->len;
    int64_t i = 0;
    while (i < shared && x[i] == y[i]) i++;
    while (i > 0 && i < shared && (x[i] & 0xC0) == 0x80) i--;
    SiyoUnits ua = siyo_rt_units_from(a, i);
    SiyoUnits ub = siyo_rt_units_from(b, i);
    for (;;) {
        int32_t u = siyo_rt_next_unit(&ua);
        int32_t v = siyo_rt_next_unit(&ub);
        if (u < 0 || v < 0) {
            return (int32_t)(siyo_rt_str_utf16_len(a) - siyo_rt_str_utf16_len(b));
        }
        if (u != v) return u - v;
    }
}

/* First unit of each run of ten decimal digits that Java's Character.digit
   accepts in the BMP (Java 21, Unicode 15); Integer.parseInt reads units, so
   supplementary digits are not accepted. */
static const uint16_t siyo_rt_digit_zeros[] = {
    0x0030, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6,
    0x0C66, 0x0CE6, 0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090, 0x17E0,
    0x1810, 0x1946, 0x19D0, 0x1A80, 0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620,
    0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0, 0xFF10
};

/* Character.digit(unit, 10). */
static int siyo_rt_digit(int32_t unit) {
    for (size_t i = 0; i < sizeof siyo_rt_digit_zeros / sizeof siyo_rt_digit_zeros[0]; i++) {
        int32_t zero = siyo_rt_digit_zeros[i];
        if (unit >= zero && unit < zero + 10) return unit - zero;
    }
    return -1;
}

/* Integer.parseInt / Long.parseLong in radix 10 for a result in [min, max],
   accumulating negatively as Java does; false where Java throws. */
static _Bool siyo_rt_parse(const SiyoStr *s, int64_t min, int64_t max, int64_t *result) {
    SiyoUnits it = siyo_rt_units_from(s, 0);
    int32_t unit = siyo_rt_next_unit(&it);
    if (unit < 0) return 0;
    _Bool negative = 0;
    int64_t limit = -max;
    if (unit < '0') {
        if (unit == '-') {
            negative = 1;
            limit = min;
        } else if (unit != '+') {
            return 0;
        }
        unit = siyo_rt_next_unit(&it);
        if (unit < 0) return 0; /* a sign alone */
    }
    int64_t multmin = limit / 10;
    int64_t value = 0;
    for (; unit >= 0; unit = siyo_rt_next_unit(&it)) {
        int digit = siyo_rt_digit(unit);
        if (digit < 0 || value < multmin) return 0;
        value *= 10;
        if (value < limit + digit) return 0;
        value -= digit;
    }
    *result = negative ? value : -value;
    return 1;
}

int32_t siyo_rt_parse_int(const SiyoStr *s) {
    int64_t value;
    if (s == NULL || !siyo_rt_parse(s, INT32_MIN, INT32_MAX, &value)) return 0;
    return (int32_t)value;
}

int64_t siyo_rt_parse_long(const SiyoStr *s) {
    if (s == NULL) {
        siyo_rt_raise_format("java.lang.NumberFormatException", "Cannot parse null string");
        return 0;
    }
    int64_t value;
    if (!siyo_rt_parse(s, INT64_MIN, INT64_MAX, &value)) {
        SiyoStr *message = siyo_rt_str_concat(siyo_rt_str_from_bytes("For input string: \"", 19, 19), s);
        siyo_rt_raise_java("java.lang.NumberFormatException",
                siyo_rt_str_concat(message, siyo_rt_str_from_bytes("\"", 1, 1)));
        return 0;
    }
    return value;
}

_Noreturn void siyo_rt_div_by_zero(void) {
    siyo_rt_fail("java.lang.ArithmeticException: / by zero\n");
}

_Noreturn void siyo_rt_stack_overflow(void) {
    siyo_rt_fail("java.lang.StackOverflowError\n");
}

_Noreturn void siyo_rt_raise_java(const char *java_class, const SiyoStr *message) {
    fflush(NULL);
    fputs(java_class, stderr);
    if (message != NULL) {
        fputs(": ", stderr);
        siyo_rt_write_str(message, stderr);
    }
    fputc('\n', stderr);
    exit(1);
}

void *siyo_rt_alloc(size_t size) {
    void *p = malloc(size == 0 ? 1 : size);
    if (p == NULL) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    return p;
}

/* First on the program thread: limit = here - stack size + headroom. */
static void *siyo_rt_program_thread(void *unused) {
    (void)unused;
    uint64_t here = (uint64_t)(uintptr_t)__builtin_frame_address(0);
    siyo_rt_stack_limit = here - SIYO_RT_STACK_SIZE + SIYO_RT_STACK_HEADROOM;
    siyo_program();
    return NULL;
}

/* Runs the program on a thread with a known stack size and waits for it. The
   program ends the process itself on a runtime error. */
int main(int argc, char **argv) {
    siyo_rt_argc = argc;
    siyo_rt_argv = argv;
    pthread_attr_t attr;
    pthread_t thread;
    int failed = pthread_attr_init(&attr);
    if (failed == 0) failed = pthread_attr_setstacksize(&attr, SIYO_RT_STACK_SIZE);
    if (failed == 0) failed = pthread_create(&thread, &attr, siyo_rt_program_thread, NULL);
    if (failed != 0) siyo_rt_fail("SiyoLLVM runtime: cannot start the program thread\n");
    pthread_attr_destroy(&attr);
    pthread_join(thread, NULL);
    fflush(NULL);
    return 0;
}
