/*
 * SiyoLLVM runtime (C11, libc only). See siyo_rt.h.
 */
#define _GNU_SOURCE

#include "siyo_rt.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <langinfo.h>
#include <locale.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
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
    if (len > 0) memcpy(buf->bytes, bytes, (size_t)len);
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

/* UTF-16 length of s, as recorded when it was made. */
static int64_t siyo_rt_str_utf16_len(const SiyoStr *s) {
    return s->utf16_len;
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

/* A raise only records the exception (see siyo_rt_raise_java), so every
   raising function returns a dummy value afterwards, which the caller's
   pending check discards. */
static void siyo_rt_null_pointer(void) {
    siyo_rt_raise_java("java.lang.NullPointerException", NULL);
}

/* A fixed ASCII exception message, made like a literal (buf NULL) so that a
   raise in a catch loop allocates nothing. */
#define SIYO_RT_STATIC_STR(name, text) \
    static const SiyoStr name = { SIYO_TAG_STR, (int32_t)(sizeof text - 1), (int64_t)(sizeof text - 1), text, NULL }

/* Raises java_class with a short ASCII message formatted from format. */
__attribute__((format(printf, 2, 3)))
static void siyo_rt_raise_format(const char *java_class, const char *format, ...) {
    char text[256];
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

/* Scratch memory the caller releases with free(); unlike siyo_rt_alloc's. */
static void *siyo_rt_scratch(size_t size) {
    void *p = malloc(size == 0 ? 1 : size);
    if (p == NULL) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    return p;
}

/* s's UTF-16 units in a temporary array the caller frees; *count gets their number. */
static uint16_t *siyo_rt_to_units(const SiyoStr *s, int64_t *count) {
    int64_t n = siyo_rt_str_utf16_len(s);
    uint16_t *units = siyo_rt_scratch((size_t)n * sizeof(uint16_t));
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
    uint16_t *out = siyo_rt_scratch((size_t)total * sizeof(uint16_t));
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

/* The locale case mapping uses. Only a test build overrides it, with a name
   newlocale cannot load, to reach the missing-locale error below. */
#ifndef SIYO_RT_CASE_LOCALE
#define SIYO_RT_CASE_LOCALE "C.UTF-8"
#endif

/* The case-mapping locale, made once and kept; (locale_t)0 if unavailable. */
static locale_t siyo_rt_case_locale(void) {
    static locale_t locale;
    static _Bool made;
    if (!made) {
        made = 1;
        locale = newlocale(LC_CTYPE_MASK, SIYO_RT_CASE_LOCALE, (locale_t)0);
    }
    return locale;
}

/* cp mapped to upper or lower case. ASCII and lone surrogates need no locale;
   any other code point does, and without it the program stops rather than
   leave the code point unmapped. */
static uint32_t siyo_rt_map_case(uint32_t cp, _Bool upper) {
    if (cp >= 0xD800 && cp <= 0xDFFF) return cp;
    if (cp < 0x80) {
        if (upper && cp >= 'a' && cp <= 'z') return cp - 32;
        if (!upper && cp >= 'A' && cp <= 'Z') return cp + 32;
        return cp;
    }
    locale_t locale = siyo_rt_case_locale();
    if (locale == (locale_t)0) {
        siyo_rt_fail("SiyoLLVM runtime: toUpper/toLower of non-ASCII text needs the " SIYO_RT_CASE_LOCALE
                " locale, which is not available\n");
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
        SIYO_RT_STATIC_STR(message, "Index 0 out of bounds for length 0");
        siyo_rt_raise_java("java.lang.StringIndexOutOfBoundsException", &message);
        return 0;
    }
    SiyoUnits it = siyo_rt_units_from(s, 0);
    return siyo_rt_next_unit(&it);
}

SiyoStr *siyo_rt_str_char_at(const SiyoStr *s, int32_t index) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    int64_t length = siyo_rt_str_utf16_len(s);
    if (index < 0 || index >= length) {
        siyo_rt_raise_format("java.lang.StringIndexOutOfBoundsException", "Index %d out of bounds for length %lld",
                (int)index, (long long)length);
        return NULL;
    }
    return siyo_rt_str_substring(s, index, index + 1);
}

static _Bool siyo_rt_is_high(uint16_t unit) {
    return unit >= 0xD800 && unit <= 0xDBFF;
}

static _Bool siyo_rt_is_low(uint16_t unit) {
    return unit >= 0xDC00 && unit <= 0xDFFF;
}

/* The code point at units[i] of n (a pair, or the unit alone), as Character.codePointAt. */
static uint32_t siyo_rt_code_point_at(const uint16_t *units, int64_t i, int64_t n) {
    if (siyo_rt_pair_at(units, i, n)) return 0x10000u + ((units[i] - 0xD800u) << 10) + (units[i + 1] - 0xDC00u);
    return units[i];
}

/*
 * Whether the m units of pat match hay (n units) at i. A pattern holding a
 * surrogate (cp set) is matched as java.util.regex matches it (StartS and
 * SliceS): code point by code point, and never from the second half of a pair.
 */
static _Bool siyo_rt_split_match(const uint16_t *hay, int64_t n, int64_t i, const uint16_t *pat, int64_t m, _Bool cp) {
    if (i + m > n) return 0;
    if (!cp) return memcmp(hay + i, pat, (size_t)m * sizeof(uint16_t)) == 0;
    if (i > 0 && siyo_rt_is_high(hay[i - 1]) && siyo_rt_is_low(hay[i])) return 0;
    int64_t x = i;
    for (int64_t j = 0; j < m;) {
        if (x >= n) return 0;
        uint32_t want = siyo_rt_code_point_at(pat, j, m);
        uint32_t have = siyo_rt_code_point_at(hay, x, n);
        if (want != have) return 0;
        int step = want >= 0x10000 ? 2 : 1;
        j += step;
        x += step;
    }
    return 1;
}

SiyoArray *siyo_rt_str_split(const SiyoStr *s, const SiyoStr *delim) {
    if (s == NULL || delim == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    SiyoArray *parts = siyo_rt_array_new(0);
    int64_t n, m;
    uint16_t *hay = siyo_rt_to_units(s, &n);
    uint16_t *needle = siyo_rt_to_units(delim, &m);
    if (m == 0) {
        /* An empty match at every index; the one at 0 adds no leading "". */
        for (int64_t i = 0; i < n; i++) siyo_rt_array_append(parts, siyo_rt_str_from_units(hay + i, 1));
        siyo_rt_array_append(parts, n == 0 ? (void *)s : siyo_rt_str_from_bytes("", 0, 0));
    } else {
        _Bool by_code_point = 0;
        for (int64_t j = 0; j < m; j++) by_code_point |= siyo_rt_is_high(needle[j]) || siyo_rt_is_low(needle[j]);
        int64_t index = 0;
        for (int64_t i = 0; i + m <= n;) {
            if (siyo_rt_split_match(hay, n, i, needle, m, by_code_point)) {
                siyo_rt_array_append(parts, siyo_rt_str_from_units(hay + index, i - index));
                index = i + m;
                i = index;
            } else {
                i++;
            }
        }
        /* No match: the string itself. */
        siyo_rt_array_append(parts, index == 0 ? (void *)s : siyo_rt_str_from_units(hay + index, n - index));
    }
    free(hay);
    free(needle);
    return parts;
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
        SIYO_RT_STATIC_STR(message, "Cannot parse null string");
        siyo_rt_raise_java("java.lang.NumberFormatException", &message);
        return 0;
    }
    int64_t value;
    if (!siyo_rt_parse(s, INT64_MIN, INT64_MAX, &value)) {
        SIYO_RT_STATIC_STR(prefix, "For input string: \"");
        SIYO_RT_STATIC_STR(quote, "\"");
        siyo_rt_raise_java("java.lang.NumberFormatException",
                siyo_rt_str_concat(siyo_rt_str_concat(&prefix, s), &quote));
        return 0;
    }
    return value;
}

/* --- any values ------------------------------------------------------------ */

static void *siyo_rt_box(uint32_t tag, int64_t value) {
    SiyoBox *box = siyo_rt_alloc(sizeof(SiyoBox));
    box->tag = tag;
    box->value = value;
    return box;
}

/* Integer.valueOf's cache: one box per value in -128..127, each filled the
   first time it is asked for (only the program thread boxes). */
static SiyoBox siyo_rt_small_ints[256];

void *siyo_rt_box_int(int32_t value) {
    if (value >= -128 && value <= 127) {
        SiyoBox *box = &siyo_rt_small_ints[value + 128];
        if (box->tag == 0) {
            box->value = value;
            box->tag = SIYO_TAG_INT;
        }
        return box;
    }
    return siyo_rt_box(SIYO_TAG_INT, value);
}

void *siyo_rt_box_long(int64_t value) {
    return siyo_rt_box(SIYO_TAG_LONG, value);
}

void *siyo_rt_box_bool(_Bool value) {
    return siyo_rt_box(SIYO_TAG_BOOL, value ? 1 : 0);
}

static uint32_t siyo_rt_tag_of(const void *value) {
    return *(const uint32_t *)value;
}

static int64_t siyo_rt_box_value(const void *value) {
    return ((const SiyoBox *)value)->value;
}

/* The Java class a non-null any value is an instance of on the JVM. */
static const char *siyo_rt_java_class_of(const void *value) {
    switch (siyo_rt_tag_of(value)) {
    case SIYO_TAG_STR: return "java.lang.String";
    case SIYO_TAG_INT: return "java.lang.Integer";
    case SIYO_TAG_LONG: return "java.lang.Long";
    case SIYO_TAG_BOOL: return "java.lang.Boolean";
    case SIYO_TAG_ARRAY: return "codeanalysis.SiyoArray";
    case SIYO_TAG_MAP: return "codeanalysis.SiyoMap";
    case SIYO_TAG_STRUCT: return "codeanalysis.SiyoObject";
    default: siyo_rt_fail("SiyoLLVM runtime: a value has an unknown type tag\n");
    }
}

/* Where HotSpot says a class comes from: the reference loads codeanalysis.*
   (SiyoArray, SiyoObject, SiyoMap) from the class path; the rest are
   java.base classes. */
static _Bool siyo_rt_app_class(const char *java_class) {
    return strncmp(java_class, "codeanalysis.", 13) == 0;
}

static const char *siyo_rt_module_of(const char *java_class) {
    return siyo_rt_app_class(java_class) ? "unnamed module of loader 'app'"
            : "module java.base of loader 'bootstrap'";
}

/* checkcast failing, worded as HotSpot does. */
static void siyo_rt_class_cast(const void *value, const char *target) {
    const char *from = siyo_rt_java_class_of(value);
    if (siyo_rt_app_class(from) != siyo_rt_app_class(target)) {
        siyo_rt_raise_format("java.lang.ClassCastException",
                "class %s cannot be cast to class %s (%s is in %s; %s is in %s)",
                from, target, from, siyo_rt_module_of(from), target, siyo_rt_module_of(target));
        return;
    }
    siyo_rt_raise_format("java.lang.ClassCastException",
            "class %s cannot be cast to class %s (%s and %s are in %s)",
            from, target, from, target, siyo_rt_module_of(from));
}

/*
 * The JVM's helpful message for unboxing null names where the null came from
 * (a call, a local, a field), which the runtime does not know; for int and
 * bool it keeps the part that does not depend on that. unboxLong's message is
 * always the same, as the null is its own parameter.
 */
static int32_t siyo_rt_unbox_int_or(const void *value, const SiyoStr *null_message) {
    if (value == NULL) {
        siyo_rt_raise_java("java.lang.NullPointerException", null_message);
        return 0;
    }
    if (siyo_rt_tag_of(value) != SIYO_TAG_INT) {
        siyo_rt_class_cast(value, "java.lang.Integer");
        return 0;
    }
    return (int32_t)siyo_rt_box_value(value);
}

int32_t siyo_rt_unbox_int(const void *value) {
    SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Integer.intValue()\"");
    return siyo_rt_unbox_int_or(value, &message);
}

int32_t siyo_rt_unbox_int_field(const void *value) {
    SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Integer.intValue()\" because the return value of "
            "\"java.util.Map.get(Object)\" is null");
    return siyo_rt_unbox_int_or(value, &message);
}

int32_t siyo_rt_unbox_int_map_get(const void *value) {
    SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Integer.intValue()\" because the return value of "
            "\"codeanalysis.SiyoMap.get(Object)\" is null");
    return siyo_rt_unbox_int_or(value, &message);
}

int32_t siyo_rt_unbox_int_map_get_str(const void *value) {
    SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Integer.intValue()\" because the return value of "
            "\"codeanalysis.SiyoMap.get(String)\" is null");
    return siyo_rt_unbox_int_or(value, &message);
}

int64_t siyo_rt_unbox_long(const void *value) {
    if (value == NULL) {
        SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Long.longValue()\" because \"value\" is null");
        siyo_rt_raise_java("java.lang.NullPointerException", &message);
        return 0;
    }
    uint32_t tag = siyo_rt_tag_of(value);
    if (tag != SIYO_TAG_INT && tag != SIYO_TAG_LONG) {
        siyo_rt_class_cast(value, "java.lang.Long");
        return 0;
    }
    return siyo_rt_box_value(value);
}

static _Bool siyo_rt_unbox_bool_or(const void *value, const SiyoStr *null_message) {
    if (value == NULL) {
        siyo_rt_raise_java("java.lang.NullPointerException", null_message);
        return 0;
    }
    if (siyo_rt_tag_of(value) != SIYO_TAG_BOOL) {
        siyo_rt_class_cast(value, "java.lang.Boolean");
        return 0;
    }
    return siyo_rt_box_value(value) != 0;
}

_Bool siyo_rt_unbox_bool(const void *value) {
    SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Boolean.booleanValue()\"");
    return siyo_rt_unbox_bool_or(value, &message);
}

_Bool siyo_rt_unbox_bool_field(const void *value) {
    SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Boolean.booleanValue()\" because the return value of "
            "\"java.util.Map.get(Object)\" is null");
    return siyo_rt_unbox_bool_or(value, &message);
}

_Bool siyo_rt_unbox_bool_map_get(const void *value) {
    SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Boolean.booleanValue()\" because the return value of "
            "\"codeanalysis.SiyoMap.get(Object)\" is null");
    return siyo_rt_unbox_bool_or(value, &message);
}

_Bool siyo_rt_unbox_bool_map_get_str(const void *value) {
    SIYO_RT_STATIC_STR(message, "Cannot invoke \"java.lang.Boolean.booleanValue()\" because the return value of "
            "\"codeanalysis.SiyoMap.get(String)\" is null");
    return siyo_rt_unbox_bool_or(value, &message);
}

const SiyoStr *siyo_rt_cast_str(const void *value) {
    if (value != NULL && siyo_rt_tag_of(value) != SIYO_TAG_STR) {
        siyo_rt_class_cast(value, "java.lang.String");
        return NULL;
    }
    return value;
}

SiyoArray *siyo_rt_cast_array(const void *value) {
    if (value != NULL && siyo_rt_tag_of(value) != SIYO_TAG_ARRAY) {
        siyo_rt_class_cast(value, "java.util.List");
        return NULL;
    }
    return (SiyoArray *)value;
}

SiyoStruct *siyo_rt_cast_struct(const void *value) {
    if (value != NULL && siyo_rt_tag_of(value) != SIYO_TAG_STRUCT) {
        siyo_rt_class_cast(value, "java.util.Map");
        return NULL;
    }
    return (SiyoStruct *)value;
}

SiyoMap *siyo_rt_cast_map(const void *value) {
    if (value != NULL && siyo_rt_tag_of(value) != SIYO_TAG_MAP) {
        siyo_rt_class_cast(value, "codeanalysis.SiyoMap");
        return NULL;
    }
    return (SiyoMap *)value;
}

SiyoStruct *siyo_rt_cast_struct_arg(const void *value) {
    if (value != NULL && siyo_rt_tag_of(value) != SIYO_TAG_STRUCT) {
        siyo_rt_class_cast(value, "java.util.LinkedHashMap");
        return NULL;
    }
    return (SiyoStruct *)value;
}

/* Recursion through nested arrays checks the stack as Siyo functions do, so
   an array that contains itself ends as StackOverflowError, not SIGSEGV. */
static void siyo_rt_check_stack(void) {
    if ((uint64_t)(uintptr_t)__builtin_frame_address(0) < siyo_rt_stack_limit) siyo_rt_stack_overflow();
}

/* SiyoArray.toString: appended into one growing buffer (see siyo_rt_str_concat). */
static SiyoStr *siyo_rt_array_to_string(const SiyoArray *array) {
    SIYO_RT_STATIC_STR(separator, ", ");
    SIYO_RT_STATIC_STR(close, "]");
    siyo_rt_check_stack();
    SiyoStr *text = siyo_rt_str_from_bytes("[", 1, 1);
    for (int32_t i = 0; i < array->len; i++) {
        if (i > 0) text = siyo_rt_str_concat(text, &separator);
        text = siyo_rt_str_concat(text, siyo_rt_any_to_string(array->items[i]));
    }
    return siyo_rt_str_concat(text, &close);
}

/* SiyoObject.toString: "Name { f: v, g: w }", or "Name {  }" with no fields. */
static SiyoStr *siyo_rt_struct_to_string(const SiyoStruct *s) {
    SIYO_RT_STATIC_STR(open, " { ");
    SIYO_RT_STATIC_STR(separator, ", ");
    SIYO_RT_STATIC_STR(colon, ": ");
    SIYO_RT_STATIC_STR(close, " }");
    siyo_rt_check_stack();
    SiyoStr *text = siyo_rt_str_concat(s->type_name, &open);
    for (int32_t i = 0; i < s->count; i++) {
        if (i > 0) text = siyo_rt_str_concat(text, &separator);
        text = siyo_rt_str_concat(text, s->names[i]);
        text = siyo_rt_str_concat(text, &colon);
        text = siyo_rt_str_concat(text, siyo_rt_any_to_string(s->values[i]));
    }
    return siyo_rt_str_concat(text, &close);
}

/* SiyoMap.toString: "{k: v, k2: w}", each key and value by String.valueOf. */
static SiyoStr *siyo_rt_map_to_string(const SiyoMap *map) {
    SIYO_RT_STATIC_STR(separator, ", ");
    SIYO_RT_STATIC_STR(colon, ": ");
    SIYO_RT_STATIC_STR(close, "}");
    siyo_rt_check_stack();
    SiyoStr *text = siyo_rt_str_from_bytes("{", 1, 1);
    _Bool first = 1;
    for (int32_t i = 0; i < map->used; i++) {
        const SiyoMapEntry *e = &map->entries[i];
        if (!e->live) continue;
        if (!first) text = siyo_rt_str_concat(text, &separator);
        first = 0;
        text = siyo_rt_str_concat(text, siyo_rt_any_to_string(e->key));
        text = siyo_rt_str_concat(text, &colon);
        text = siyo_rt_str_concat(text, siyo_rt_any_to_string(e->value));
    }
    return siyo_rt_str_concat(text, &close);
}

SiyoStr *siyo_rt_any_to_string(const void *value) {
    if (value == NULL) return siyo_rt_str_from_bytes("null", 4, 4);
    switch (siyo_rt_tag_of(value)) {
    case SIYO_TAG_STR: return (SiyoStr *)value;
    case SIYO_TAG_INT:
    case SIYO_TAG_LONG: return siyo_rt_str_from_long(siyo_rt_box_value(value));
    case SIYO_TAG_BOOL: return siyo_rt_str_from_bool(siyo_rt_box_value(value) != 0);
    case SIYO_TAG_ARRAY: return siyo_rt_array_to_string(value);
    case SIYO_TAG_STRUCT: return siyo_rt_struct_to_string(value);
    case SIYO_TAG_MAP: return siyo_rt_map_to_string(value);
    default: siyo_rt_java_class_of(value); return NULL;
    }
}

/* List.equals between two arrays: the same length and equal elements in order. */
static _Bool siyo_rt_array_equals(const SiyoArray *a, const SiyoArray *b) {
    siyo_rt_check_stack();
    if (a->len != b->len) return 0;
    for (int32_t i = 0; i < a->len; i++) {
        if (!siyo_rt_any_equals(a->items[i], b->items[i])) return 0;
    }
    return 1;
}

static int32_t siyo_rt_field_index(const SiyoStruct *s, const SiyoStr *name);

/* AbstractMap.equals between two structs: the same size, and every field of a
   present in b with an equal value; order and type names do not matter. */
static _Bool siyo_rt_struct_equals(const SiyoStruct *a, const SiyoStruct *b) {
    siyo_rt_check_stack();
    if (a->count != b->count) return 0;
    for (int32_t i = 0; i < a->count; i++) {
        int32_t j = siyo_rt_field_index(b, a->names[i]);
        if (j < 0 || !siyo_rt_any_equals(a->values[i], b->values[j])) return 0;
    }
    return 1;
}

_Bool siyo_rt_any_equals(const void *a, const void *b) {
    if (a == b) return 1;
    if (a == NULL || b == NULL) return 0;
    uint32_t tag = siyo_rt_tag_of(a);
    if (tag != siyo_rt_tag_of(b)) return 0;
    switch (tag) {
    case SIYO_TAG_STR: return siyo_rt_str_eq(a, b);
    case SIYO_TAG_INT:
    case SIYO_TAG_LONG:
    case SIYO_TAG_BOOL: return siyo_rt_box_value(a) == siyo_rt_box_value(b);
    case SIYO_TAG_ARRAY: return siyo_rt_array_equals(a, b);
    case SIYO_TAG_STRUCT: return siyo_rt_struct_equals(a, b);
    case SIYO_TAG_MAP: return 0; /* Object.equals: a == b was checked above */
    default: siyo_rt_java_class_of(a); return 0;
    }
}

/* Prints a string or a boxed value without allocating, so printing those in a
   loop does not grow memory; an array, a struct or a map is built into one string first, as
   PrintStream.print(Object) does, so a failure prints nothing of it. */
void siyo_rt_print_any(const void *value) {
    if (value == NULL) {
        fputs("null", stdout);
        return;
    }
    switch (siyo_rt_tag_of(value)) {
    case SIYO_TAG_STR: siyo_rt_print_str(value); return;
    case SIYO_TAG_INT:
    case SIYO_TAG_LONG: siyo_rt_print_long(siyo_rt_box_value(value)); return;
    case SIYO_TAG_BOOL: siyo_rt_print_bool(siyo_rt_box_value(value) != 0); return;
    case SIYO_TAG_ARRAY: siyo_rt_print_str(siyo_rt_array_to_string(value)); return;
    case SIYO_TAG_STRUCT: siyo_rt_print_str(siyo_rt_struct_to_string(value)); return;
    case SIYO_TAG_MAP: siyo_rt_print_str(siyo_rt_map_to_string(value)); return;
    default: siyo_rt_java_class_of(value); return;
    }
}

/* --- arrays ---------------------------------------------------------------- */

static void siyo_rt_index_out_of_bounds(int32_t index, int32_t len) {
    siyo_rt_raise_format("java.lang.IndexOutOfBoundsException", "Index %d out of bounds for length %d",
            (int)index, (int)len);
}

SiyoArray *siyo_rt_array_new(int32_t capacity) {
    SiyoArray *array = siyo_rt_alloc(sizeof(SiyoArray));
    array->tag = SIYO_TAG_ARRAY;
    array->len = 0;
    array->cap = capacity > 0 ? capacity : 0;
    array->items = array->cap > 0 ? siyo_rt_alloc((size_t)array->cap * sizeof(void *)) : NULL;
    return array;
}

void siyo_rt_array_push(SiyoArray *array, void *value) {
    if (array == NULL) {
        siyo_rt_null_pointer();
        return;
    }
    siyo_rt_array_append(array, value);
}

void siyo_rt_array_append(SiyoArray *array, void *value) {
    if (array->len == array->cap) {
        /* Only this array points at its items, so the block may move. */
        if (array->cap == INT32_MAX) siyo_rt_fail("java.lang.OutOfMemoryError\n");
        int64_t cap = array->cap < 4 ? 4 : (int64_t)array->cap * 2;
        if (cap > INT32_MAX) cap = INT32_MAX;
        void **items = realloc(array->items, (size_t)cap * sizeof(void *));
        if (items == NULL) siyo_rt_fail("java.lang.OutOfMemoryError\n");
        array->items = items;
        array->cap = (int32_t)cap;
    }
    array->items[array->len++] = value;
}

SiyoArray *siyo_rt_range(int32_t from, int32_t to) {
    if (from >= to) return siyo_rt_array_new(0);
    int64_t n = (int64_t)to - from;
    if (n > INT32_MAX) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    SiyoArray *array = siyo_rt_array_new((int32_t)n);
    for (int64_t i = from; i < to; i++) siyo_rt_array_append(array, siyo_rt_box_int((int32_t)i));
    return array;
}

void *siyo_rt_array_get(SiyoArray *array, int32_t index) {
    if (array == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    if (index < 0 || index >= array->len) {
        siyo_rt_index_out_of_bounds(index, array->len);
        return NULL;
    }
    return array->items[index];
}

void *siyo_rt_array_set(SiyoArray *array, int32_t index, void *value) {
    if (array == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    if (index < 0 || index >= array->len) {
        siyo_rt_index_out_of_bounds(index, array->len);
        return NULL;
    }
    void *old = array->items[index];
    array->items[index] = value;
    return old;
}

/* List.remove(index) on a non-null array: the removed element. */
static void *siyo_rt_array_remove(SiyoArray *array, int32_t index) {
    if (index < 0 || index >= array->len) {
        siyo_rt_index_out_of_bounds(index, array->len);
        return NULL;
    }
    void *old = array->items[index];
    memmove(array->items + index, array->items + index + 1, (size_t)(array->len - index - 1) * sizeof(void *));
    array->len--;
    return old;
}

void *siyo_rt_array_pop(SiyoArray *array) {
    if (array == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    return siyo_rt_array_remove(array, array->len - 1);
}

void siyo_rt_array_remove_at(SiyoArray *array, int32_t index) {
    if (array == NULL) {
        siyo_rt_null_pointer();
        return;
    }
    siyo_rt_array_remove(array, index);
}

int32_t siyo_rt_array_len(SiyoArray *array) {
    if (array == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    return array->len;
}

/* --- exceptions ------------------------------------------------------------ */

_Thread_local _Bool siyo_rt_pending_flag;

/* The pending exception: a Siyo throw's payload (java_class NULL), or a Java
   exception's class and message (NULL for none). */
static _Thread_local const char *siyo_rt_pending_class;
static _Thread_local const SiyoStr *siyo_rt_pending_message;
static _Thread_local void *siyo_rt_pending_payload;

void siyo_rt_throw(void *payload) {
    siyo_rt_pending_class = NULL;
    siyo_rt_pending_message = NULL;
    siyo_rt_pending_payload = payload;
    siyo_rt_pending_flag = 1;
}

void siyo_rt_raise_java(const char *java_class, const SiyoStr *message) {
    siyo_rt_pending_class = java_class;
    siyo_rt_pending_message = message;
    siyo_rt_pending_payload = NULL;
    siyo_rt_pending_flag = 1;
}

void *siyo_rt_catch(void) {
    siyo_rt_pending_flag = 0;
    if (siyo_rt_pending_class == NULL) return siyo_rt_pending_payload;
    const SiyoStr *message = siyo_rt_pending_message;
    if (message != NULL && message->len > 0) return (void *)message;
    /* getSimpleName(): what follows the last '.' of the class name. */
    const char *simple = strrchr(siyo_rt_pending_class, '.');
    simple = simple != NULL ? simple + 1 : siyo_rt_pending_class;
    size_t len = strlen(simple);
    return siyo_rt_str_from_bytes(simple, (int64_t)len, (int64_t)len);
}

_Noreturn void siyo_rt_uncaught(void) {
    fflush(NULL);
    if (siyo_rt_pending_class == NULL) {
        /* SiyoThrow's message is String.valueOf(payload). */
        fputs("codeanalysis.SiyoThrow: ", stderr);
        siyo_rt_write_str(siyo_rt_any_to_string(siyo_rt_pending_payload), stderr);
    } else {
        fputs(siyo_rt_pending_class, stderr);
        if (siyo_rt_pending_message != NULL) {
            fputs(": ", stderr);
            siyo_rt_write_str(siyo_rt_pending_message, stderr);
        }
    }
    fputc('\n', stderr);
    exit(1);
}

_Noreturn void siyo_rt_uncaught_in_initializer(void) {
    siyo_rt_fail("java.lang.ExceptionInInitializerError\n");
}

void siyo_rt_div_by_zero(void) {
    SIYO_RT_STATIC_STR(message, "/ by zero");
    siyo_rt_raise_java("java.lang.ArithmeticException", &message);
}

void siyo_rt_unbox_null_literal(int32_t tag) {
    SIYO_RT_STATIC_STR(on_bool, "Cannot invoke \"java.lang.Boolean.booleanValue()\" because \"null\" is null");
    SIYO_RT_STATIC_STR(on_int, "Cannot invoke \"java.lang.Integer.intValue()\" because \"null\" is null");
    siyo_rt_raise_java("java.lang.NullPointerException", tag == SIYO_TAG_BOOL ? &on_bool : &on_int);
}

_Noreturn void siyo_rt_stack_overflow(void) {
    siyo_rt_fail("java.lang.StackOverflowError\n");
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
   program ends the process itself on an uncaught exception or fatal error. */
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

/* --- structs --------------------------------------------------------------- */

SiyoStruct *siyo_rt_struct_new(const SiyoStr *type_name, int32_t count, const SiyoStr **names) {
    SiyoStruct *s = siyo_rt_alloc(sizeof(SiyoStruct));
    s->tag = SIYO_TAG_STRUCT;
    s->count = count;
    s->type_name = type_name;
    s->names = names;
    s->values = count > 0 ? siyo_rt_alloc((size_t)count * sizeof(void *)) : NULL;
    for (int32_t i = 0; i < count; i++) s->values[i] = NULL;
    return s;
}

void siyo_rt_struct_init(SiyoStruct *s, int32_t index, void *value) {
    s->values[index] = value;
}

/* The index of the field called name, or -1: the IR interns names, so a
   pointer comparison almost always decides. */
static int32_t siyo_rt_field_index(const SiyoStruct *s, const SiyoStr *name) {
    for (int32_t i = 0; i < s->count; i++) {
        if (s->names[i] == name) return i;
    }
    for (int32_t i = 0; i < s->count; i++) {
        if (siyo_rt_str_eq(s->names[i], name)) return i;
    }
    return -1;
}

void *siyo_rt_struct_get(SiyoStruct *s, const SiyoStr *name) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    int32_t i = siyo_rt_field_index(s, name);
    return i < 0 ? NULL : s->values[i];
}

void siyo_rt_struct_set(SiyoStruct *s, const SiyoStr *name, void *value) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return;
    }
    int32_t i = siyo_rt_field_index(s, name);
    if (i >= 0) {
        s->values[i] = value;
        return;
    }
    /* A field the literal left out: append it, as LinkedHashMap.put does. */
    size_t n = (size_t)s->count;
    const SiyoStr **names = siyo_rt_alloc((n + 1) * sizeof(SiyoStr *));
    void **values = siyo_rt_alloc((n + 1) * sizeof(void *));
    for (size_t k = 0; k < n; k++) {
        names[k] = s->names[k];
        values[k] = s->values[k];
    }
    names[n] = name;
    values[n] = value;
    s->names = names;
    s->values = values;
    s->count++;
}

SiyoStr *siyo_rt_type_name(const void *value) {
    if (value != NULL && siyo_rt_tag_of(value) == SIYO_TAG_STRUCT) return (SiyoStr *)((const SiyoStruct *)value)->type_name;
    return siyo_rt_str_from_bytes("", 0, 0);
}

/* --- maps ------------------------------------------------------------------ */

static uint32_t siyo_rt_mix(uint64_t x) {
    x ^= x >> 33;
    x *= UINT64_C(0xff51afd7ed558ccd);
    x ^= x >> 33;
    return (uint32_t)x;
}

/*
 * A hash consistent with siyo_rt_any_equals: a string by its bytes (equal
 * strings have the same canonical bytes), a box by its type and value, an
 * array by its elements in order, a struct by its fields in any order (type
 * name ignored), a map by identity. As in Java, a key mutated while in a map
 * is not found again by its new contents.
 */
static uint32_t siyo_rt_any_hash(const void *value) {
    if (value == NULL) return 0;
    uint32_t tag = siyo_rt_tag_of(value);
    switch (tag) {
    case SIYO_TAG_STR: {
        const SiyoStr *s = value;
        uint32_t h = 2166136261u;
        for (int64_t i = 0; i < s->len; i++) {
            h ^= (unsigned char)s->data[i];
            h *= 16777619u;
        }
        return h;
    }
    case SIYO_TAG_INT:
    case SIYO_TAG_LONG:
    case SIYO_TAG_BOOL: return siyo_rt_mix((uint64_t)siyo_rt_box_value(value) * 8 + tag);
    case SIYO_TAG_ARRAY: {
        const SiyoArray *a = value;
        siyo_rt_check_stack();
        uint32_t h = 1;
        for (int32_t i = 0; i < a->len; i++) h = 31 * h + siyo_rt_any_hash(a->items[i]);
        return h;
    }
    case SIYO_TAG_STRUCT: {
        const SiyoStruct *st = value;
        siyo_rt_check_stack();
        uint32_t h = 0;
        for (int32_t i = 0; i < st->count; i++) h += siyo_rt_any_hash(st->names[i]) ^ siyo_rt_any_hash(st->values[i]);
        return h;
    }
    case SIYO_TAG_MAP: return siyo_rt_mix((uint64_t)(uintptr_t)value);
    default: siyo_rt_java_class_of(value); return 0;
    }
}

SiyoMap *siyo_rt_map_new(void) {
    SiyoMap *map = siyo_rt_alloc(sizeof(SiyoMap));
    map->tag = SIYO_TAG_MAP;
    map->size = 0;
    map->used = 0;
    map->cap = 0;
    map->entries = NULL;
    map->slots = NULL;
    map->slot_count = 0;
    return map;
}

/* The entry number holding key, or -1. Dead entries never match. */
static int32_t siyo_rt_map_find(const SiyoMap *map, const void *key, uint32_t hash) {
    if (map->slot_count == 0) return -1;
    uint32_t mask = (uint32_t)map->slot_count - 1;
    for (uint32_t i = hash & mask;; i = (i + 1) & mask) {
        int32_t n = map->slots[i];
        if (n < 0) return -1;
        const SiyoMapEntry *e = &map->entries[n];
        if (e->live && e->hash == hash && siyo_rt_any_equals(e->key, key)) return n;
    }
}

static void siyo_rt_map_index(SiyoMap *map, int32_t n) {
    uint32_t mask = (uint32_t)map->slot_count - 1;
    uint32_t i = map->entries[n].hash & mask;
    while (map->slots[i] >= 0) i = (i + 1) & mask;
    map->slots[i] = n;
}

/* Makes room for one more entry: drops the dead entries, keeping the order,
   and doubles the room when that frees too little; then rebuilds the index. */
static void siyo_rt_map_grow(SiyoMap *map) {
    int32_t live = 0;
    for (int32_t i = 0; i < map->used; i++) {
        if (map->entries[i].live) map->entries[live++] = map->entries[i];
    }
    map->used = live;
    if (live >= map->cap / 2) {
        if (map->cap >= INT32_MAX / 4) siyo_rt_fail("java.lang.OutOfMemoryError\n");
        int32_t cap = map->cap < 8 ? 8 : map->cap * 2;
        SiyoMapEntry *entries = realloc(map->entries, (size_t)cap * sizeof(SiyoMapEntry));
        if (entries == NULL) siyo_rt_fail("java.lang.OutOfMemoryError\n");
        map->entries = entries;
        map->cap = cap;
        map->slot_count = cap * 2;
        free(map->slots);
        map->slots = siyo_rt_alloc((size_t)map->slot_count * sizeof(int32_t));
    }
    for (int32_t i = 0; i < map->slot_count; i++) map->slots[i] = -1;
    for (int32_t i = 0; i < map->used; i++) siyo_rt_map_index(map, i);
}

void siyo_rt_map_insert(SiyoMap *map, void *key, void *value) {
    uint32_t hash = siyo_rt_any_hash(key);
    int32_t n = siyo_rt_map_find(map, key, hash);
    if (n >= 0) {
        map->entries[n].value = value;
        return;
    }
    if (map->used == map->cap) siyo_rt_map_grow(map);
    n = map->used++;
    map->entries[n] = (SiyoMapEntry){ key, value, hash, 1 };
    siyo_rt_map_index(map, n);
    map->size++;
}

void *siyo_rt_map_get(SiyoMap *map, void *key) {
    if (map == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    int32_t n = siyo_rt_map_find(map, key, siyo_rt_any_hash(key));
    return n < 0 ? NULL : map->entries[n].value;
}

void siyo_rt_map_put(SiyoMap *map, void *key, void *value) {
    if (map == NULL) {
        siyo_rt_null_pointer();
        return;
    }
    siyo_rt_map_insert(map, key, value);
}

_Bool siyo_rt_map_has(SiyoMap *map, void *key) {
    if (map == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    return siyo_rt_map_find(map, key, siyo_rt_any_hash(key)) >= 0;
}

/* The entry stays in the index, dead, until the next rebuild. */
void *siyo_rt_map_remove(SiyoMap *map, void *key) {
    if (map == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    int32_t n = siyo_rt_map_find(map, key, siyo_rt_any_hash(key));
    if (n < 0) return NULL;
    SiyoMapEntry *e = &map->entries[n];
    void *old = e->value;
    e->live = 0;
    e->key = NULL;
    e->value = NULL;
    map->size--;
    return old;
}

int32_t siyo_rt_map_size(SiyoMap *map) {
    if (map == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    return map->size;
}

int32_t siyo_rt_map_len(SiyoMap *map) {
    return map == NULL ? 0 : map->size;
}

SiyoArray *siyo_rt_map_keys(SiyoMap *map) {
    if (map == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    SiyoArray *keys = siyo_rt_array_new(map->size);
    for (int32_t i = 0; i < map->used; i++) {
        if (map->entries[i].live) siyo_rt_array_append(keys, map->entries[i].key);
    }
    return keys;
}

/* --- std/io and std/os ------------------------------------------------------ */

/* A growable byte buffer for building file contents, paths and names. */
typedef struct SiyoBytes {
    unsigned char *bytes;
    size_t len;
    size_t cap;
} SiyoBytes;

static void siyo_rt_bytes_reserve(SiyoBytes *b, size_t extra) {
    if (extra > SIZE_MAX / 2 - b->len) siyo_rt_fail("java.lang.OutOfMemoryError\n");
    if (b->len + extra <= b->cap) return;
    size_t cap = b->cap < 64 ? 64 : b->cap;
    while (cap < b->len + extra) cap *= 2;
    unsigned char *bytes = siyo_rt_alloc(cap);
    if (b->len > 0) memcpy(bytes, b->bytes, b->len);
    free(b->bytes);
    b->bytes = bytes;
    b->cap = cap;
}

static void siyo_rt_bytes_put(SiyoBytes *b, const void *data, size_t n) {
    siyo_rt_bytes_reserve(b, n);
    if (n > 0) memcpy(b->bytes + b->len, data, n);
    b->len += n;
}

static void siyo_rt_bytes_byte(SiyoBytes *b, unsigned char c) {
    siyo_rt_bytes_put(b, &c, 1);
}

/* A string of ASCII bytes. */
static SiyoStr *siyo_rt_str_ascii(const char *text) {
    size_t len = strlen(text);
    return siyo_rt_str_from_bytes(text, (int64_t)len, (int64_t)len);
}

/*
 * Java's sun.jnu.encoding, which the JVM uses for file names, arguments, the
 * environment and user.dir: UTF-8 when the locale (LC_ALL, LC_CTYPE or LANG,
 * as setlocale(LC_ALL, "") reads them) has the UTF-8 codeset; otherwise
 * US-ASCII, as in the C and POSIX locales. Read without changing the
 * process's locale.
 */
static _Bool siyo_rt_jnu_utf8(void) {
    static int state; /* 0 not read yet, 1 UTF-8, 2 US-ASCII */
    if (state == 0) {
        _Bool utf8 = 0;
        locale_t locale = newlocale(LC_ALL_MASK, "", (locale_t)0);
        if (locale != (locale_t)0) {
            const char *codeset = nl_langinfo_l(CODESET, locale);
            utf8 = codeset != NULL && strcmp(codeset, "UTF-8") == 0;
            freelocale(locale);
        }
        state = utf8 ? 1 : 2;
    }
    return state == 1;
}

static _Bool siyo_rt_continuation(unsigned char b) {
    return (b & 0xC0) == 0x80;
}

/*
 * One step of Java's UTF-8 decoder (sun.nio.cs.UTF_8) over the n > 0 bytes at
 * p, the rest of the input: returns how many bytes it consumes, setting *cp
 * to the character, or to U+FFFD when those bytes are malformed and replaced
 * by one U+FFFD. Surrogates encoded in UTF-8 and code points past U+10FFFF
 * are malformed; an incomplete sequence at the end is one replacement.
 */
static size_t siyo_rt_utf8_step(const unsigned char *p, size_t n, uint32_t *cp) {
    unsigned b1 = p[0];
    *cp = 0xFFFD;
    if (b1 < 0x80) {
        *cp = b1;
        return 1;
    }
    if (b1 >= 0xC2 && b1 <= 0xDF) {
        if (n < 2 || !siyo_rt_continuation(p[1])) return 1;
        *cp = ((b1 & 0x1Fu) << 6) | (p[1] & 0x3Fu);
        return 2;
    }
    if (b1 >= 0xE0 && b1 <= 0xEF) {
        _Bool bad2 = n > 1 && ((b1 == 0xE0 && (p[1] & 0xE0) == 0x80) || !siyo_rt_continuation(p[1]));
        if (n < 3) return bad2 ? 1 : n;
        if (bad2) return 1;
        if (!siyo_rt_continuation(p[2])) return 2;
        uint32_t c = ((b1 & 0x0Fu) << 12) | ((p[1] & 0x3Fu) << 6) | (p[2] & 0x3Fu);
        if (c < 0xD800 || c > 0xDFFF) *cp = c;
        return 3;
    }
    if (b1 >= 0xF0 && b1 <= 0xF7) {
        if (n < 4) {
            unsigned b2 = n > 1 ? p[1] : 0;
            if (n > 1 && ((b1 == 0xF0 && (b2 < 0x90 || b2 > 0xBF)) || (b1 == 0xF4 && (b2 & 0xF0) != 0x80)
                          || !siyo_rt_continuation(p[1]))) {
                return 1;
            }
            if (n > 2 && !siyo_rt_continuation(p[2])) return 2;
            return n;
        }
        uint32_t c = ((b1 & 0x07u) << 18) | ((p[1] & 0x3Fu) << 12) | ((p[2] & 0x3Fu) << 6) | (p[3] & 0x3Fu);
        if (siyo_rt_continuation(p[1]) && siyo_rt_continuation(p[2]) && siyo_rt_continuation(p[3])
            && c >= 0x10000 && c <= 0x10FFFF) {
            *cp = c;
            return 4;
        }
        unsigned b2 = p[1];
        if (b1 > 0xF4 || (b1 == 0xF0 && (b2 < 0x90 || b2 > 0xBF)) || (b1 == 0xF4 && (b2 & 0xF0) != 0x80)
            || !siyo_rt_continuation(p[1])) {
            return 1;
        }
        return siyo_rt_continuation(p[2]) ? 3 : 2;
    }
    return 1;
}

/* n bytes decoded as Java's UTF-8 decoder with REPLACE does: valid UTF-8 out. */
static SiyoStr *siyo_rt_decode_utf8(const unsigned char *p, size_t n) {
    SiyoBytes out = {0};
    siyo_rt_bytes_reserve(&out, n + 1);
    for (size_t i = 0; i < n;) {
        uint32_t cp;
        size_t step = siyo_rt_utf8_step(p + i, n - i, &cp);
        if (cp < 0x80) {
            siyo_rt_bytes_byte(&out, (unsigned char)cp);
        } else if (cp == 0xFFFD) {
            siyo_rt_bytes_put(&out, "\xEF\xBF\xBD", 3);
        } else {
            siyo_rt_bytes_put(&out, p + i, step);
        }
        i += step;
    }
    SiyoStr *s = siyo_rt_str_from_bytes((const char *)out.bytes, (int64_t)out.len,
                                        siyo_rt_count_units(out.bytes, (int64_t)out.len));
    free(out.bytes);
    return s;
}

/* Bytes from the operating system (an argument, a file name, the
   environment, the working directory) as the JVM decodes them with
   sun.jnu.encoding: UTF-8 with replacement, or US-ASCII, each byte past
   0x7F becoming U+FFFD. */
static SiyoStr *siyo_rt_from_os(const char *bytes) {
    const unsigned char *p = (const unsigned char *)bytes;
    size_t n = strlen(bytes);
    if (siyo_rt_jnu_utf8()) return siyo_rt_decode_utf8(p, n);
    SiyoBytes out = {0};
    int64_t units = 0;
    for (size_t i = 0; i < n; i++, units++) {
        if (p[i] < 0x80) siyo_rt_bytes_byte(&out, p[i]);
        else siyo_rt_bytes_put(&out, "\xEF\xBF\xBD", 3);
    }
    SiyoStr *s = siyo_rt_str_from_bytes((const char *)out.bytes, (int64_t)out.len, units);
    free(out.bytes);
    return s;
}

/* Appends s's WTF-8 bytes as Java encodes the string for the operating
   system: UTF-8 with each lone surrogate as '?', or US-ASCII with each
   UTF-16 unit past 0x7F as '?' (a 4-byte sequence is two units). */
static void siyo_rt_to_os(SiyoBytes *out, const unsigned char *p, size_t n) {
    _Bool utf8 = siyo_rt_jnu_utf8();
    for (size_t i = 0; i < n;) {
        int len = siyo_rt_seq_len(p[i]);
        if (len == 1) {
            siyo_rt_bytes_byte(out, p[i]);
        } else if (!utf8) {
            siyo_rt_bytes_put(out, len == 4 ? "??" : "?", len == 4 ? 2 : 1);
        } else if (len == 3 && siyo_rt_surrogate_at(p + i) != 0) {
            siyo_rt_bytes_byte(out, '?');
        } else {
            siyo_rt_bytes_put(out, p + i, (size_t)len);
        }
        i += (size_t)len;
    }
}

/*
 * A path as java.io.File holds and passes it: normal is the Siyo string with
 * repeated '/' collapsed and a trailing '/' dropped (UnixFileSystem.normalize),
 * os its NUL-terminated bytes for the operating system, invalid whether it
 * holds a NUL character (File.isInvalid).
 */
typedef struct SiyoPath {
    SiyoStr *normal;
    char *os;
    _Bool invalid;
} SiyoPath;

/* Fills *path from s; a null s raises NullPointerException (new File(null)) and returns 0. */
static _Bool siyo_rt_path(const SiyoStr *s, SiyoPath *path) {
    if (s == NULL) {
        siyo_rt_null_pointer();
        return 0;
    }
    const unsigned char *p = (const unsigned char *)s->data;
    size_t n = (size_t)s->len;
    SiyoBytes normal = {0};
    int64_t removed = 0;
    for (size_t i = 0; i < n; i++) {
        if (p[i] == '/' && normal.len > 0 && normal.bytes[normal.len - 1] == '/') {
            removed++;
            continue;
        }
        siyo_rt_bytes_byte(&normal, p[i]);
    }
    if (normal.len > 1 && normal.bytes[normal.len - 1] == '/') {
        normal.len--;
        removed++;
    }
    path->normal = siyo_rt_str_from_bytes((const char *)normal.bytes, (int64_t)normal.len, s->utf16_len - removed);
    path->invalid = normal.len > 0 && memchr(normal.bytes, 0, normal.len) != NULL;
    SiyoBytes os = {0};
    siyo_rt_to_os(&os, normal.bytes, normal.len);
    siyo_rt_bytes_byte(&os, 0);
    path->os = (char *)os.bytes;
    free(normal.bytes);
    return 1;
}

/* Raises java.io.FileNotFoundException "<path> (<strerror>)", as the JVM's
   FileInputStream and FileOutputStream report a failed open. */
static void siyo_rt_file_not_found(const SiyoPath *path, int error) {
    const char *reason = strerror(error);
    SiyoStr *message = siyo_rt_str_concat(path->normal, siyo_rt_str_ascii(" ("));
    message = siyo_rt_str_concat(message, siyo_rt_str_ascii(reason));
    message = siyo_rt_str_concat(message, siyo_rt_str_ascii(")"));
    siyo_rt_raise_java("java.io.FileNotFoundException", message);
}

static void siyo_rt_invalid_path(void) {
    SIYO_RT_STATIC_STR(message, "Invalid file path");
    siyo_rt_raise_java("java.io.FileNotFoundException", &message);
}

/* Raises java.io.IOException with strerror(error), for a read or write that fails after the open. */
static void siyo_rt_io_error(int error) {
    siyo_rt_raise_java("java.io.IOException", siyo_rt_str_ascii(strerror(error)));
}

/* Opens path as FileInputStream/FileOutputStream do (a directory is EISDIR); -1 after raising. */
static int siyo_rt_open(const SiyoPath *path, int flags) {
    if (path->invalid) {
        siyo_rt_invalid_path();
        return -1;
    }
    int fd;
    do {
        fd = open(path->os, flags | O_CLOEXEC, 0666);
    } while (fd < 0 && errno == EINTR);
    if (fd < 0) {
        siyo_rt_file_not_found(path, errno);
        return -1;
    }
    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISDIR(st.st_mode)) {
        close(fd);
        siyo_rt_file_not_found(path, EISDIR);
        return -1;
    }
    return fd;
}

/* The whole file at s decoded as Java's FileReader does (UTF-8, replacing
   malformed input); NULL after raising. */
static SiyoStr *siyo_rt_read_text(const SiyoStr *s) {
    SiyoPath path;
    if (!siyo_rt_path(s, &path)) return NULL;
    int fd = siyo_rt_open(&path, O_RDONLY);
    if (fd < 0) return NULL;
    SiyoBytes data = {0};
    for (;;) {
        siyo_rt_bytes_reserve(&data, 65536);
        ssize_t got = read(fd, data.bytes + data.len, data.cap - data.len);
        if (got < 0 && errno == EINTR) continue;
        if (got < 0) {
            int error = errno;
            close(fd);
            free(data.bytes);
            siyo_rt_io_error(error);
            return NULL;
        }
        if (got == 0) break;
        data.len += (size_t)got;
    }
    close(fd);
    SiyoStr *text = siyo_rt_decode_utf8(data.bytes, data.len);
    free(data.bytes);
    return text;
}

/* BufferedReader.readLine over text: lines end at "\n", "\r" or "\r\n"; a
   terminator at the very end starts no further line. */
static SiyoArray *siyo_rt_lines_of(const SiyoStr *text) {
    SiyoArray *lines = siyo_rt_array_new(0);
    const unsigned char *p = (const unsigned char *)text->data;
    size_t n = (size_t)text->len;
    size_t start = 0;
    for (size_t i = 0; i < n; i++) {
        if (p[i] != '\n' && p[i] != '\r') continue;
        int64_t len = (int64_t)(i - start);
        siyo_rt_array_append(lines, siyo_rt_str_from_bytes((const char *)p + start, len,
                                                           siyo_rt_count_units(p + start, len)));
        if (p[i] == '\r' && i + 1 < n && p[i + 1] == '\n') i++;
        start = i + 1;
    }
    if (start < n) {
        int64_t len = (int64_t)(n - start);
        siyo_rt_array_append(lines, siyo_rt_str_from_bytes((const char *)p + start, len,
                                                           siyo_rt_count_units(p + start, len)));
    }
    return lines;
}

SiyoArray *siyo_rt_io_read_lines(const SiyoStr *path) {
    SiyoStr *text = siyo_rt_read_text(path);
    if (text == NULL) return NULL;
    return siyo_rt_lines_of(text);
}

SiyoStr *siyo_rt_io_read_file(const SiyoStr *path) {
    SiyoStr *text = siyo_rt_read_text(path);
    if (text == NULL) return NULL;
    SiyoArray *lines = siyo_rt_lines_of(text);
    SiyoBytes out = {0};
    int64_t units = 0;
    for (int32_t i = 0; i < lines->len; i++) {
        const SiyoStr *line = lines->items[i];
        if (i > 0) {
            siyo_rt_bytes_byte(&out, '\n');
            units++;
        }
        siyo_rt_bytes_put(&out, line->data, (size_t)line->len);
        units += line->utf16_len;
    }
    SiyoStr *result = siyo_rt_str_from_bytes((const char *)out.bytes, (int64_t)out.len, units);
    free(out.bytes);
    return result;
}

/* FileWriter(path, append).write(content).close(): opens (creating, and
   truncating unless appending), then writes content as UTF-8 with each lone
   surrogate as '?'. A null content raises NullPointerException after the open. */
static void siyo_rt_write(const SiyoStr *s, const SiyoStr *content, _Bool append) {
    SiyoPath path;
    if (!siyo_rt_path(s, &path)) return;
    int fd = siyo_rt_open(&path, O_WRONLY | O_CREAT | (append ? O_APPEND : O_TRUNC));
    if (fd < 0) return;
    if (content == NULL) {
        close(fd);
        siyo_rt_null_pointer();
        return;
    }
    SiyoBytes out = {0};
    const unsigned char *p = (const unsigned char *)content->data;
    size_t n = (size_t)content->len;
    for (size_t i = 0; i < n;) {
        int len = siyo_rt_seq_len(p[i]);
        if (len == 3 && siyo_rt_surrogate_at(p + i) != 0) siyo_rt_bytes_byte(&out, '?');
        else siyo_rt_bytes_put(&out, p + i, (size_t)len);
        i += (size_t)len;
    }
    size_t done = 0;
    while (done < out.len) {
        ssize_t wrote = write(fd, out.bytes + done, out.len - done);
        if (wrote < 0 && errno == EINTR) continue;
        if (wrote < 0) {
            int error = errno;
            close(fd);
            free(out.bytes);
            siyo_rt_io_error(error);
            return;
        }
        done += (size_t)wrote;
    }
    free(out.bytes);
    if (close(fd) != 0 && errno != EINTR) siyo_rt_io_error(errno);
}

void siyo_rt_io_write_file(const SiyoStr *path, const SiyoStr *content) {
    siyo_rt_write(path, content, 0);
}

void siyo_rt_io_append_file(const SiyoStr *path, const SiyoStr *content) {
    siyo_rt_write(path, content, 1);
}

/* stat of a valid path: File.exists, isFile and isDirectory (links followed). */
static _Bool siyo_rt_stat(const SiyoStr *s, struct stat *st, _Bool *found) {
    SiyoPath path;
    *found = 0;
    if (!siyo_rt_path(s, &path)) return 0;
    *found = !path.invalid && stat(path.os, st) == 0;
    return 1;
}

_Bool siyo_rt_io_exists(const SiyoStr *path) {
    struct stat st;
    _Bool found;
    siyo_rt_stat(path, &st, &found);
    return found;
}

_Bool siyo_rt_io_is_file(const SiyoStr *path) {
    struct stat st;
    _Bool found;
    siyo_rt_stat(path, &st, &found);
    return found && S_ISREG(st.st_mode);
}

_Bool siyo_rt_io_is_dir(const SiyoStr *path) {
    struct stat st;
    _Bool found;
    siyo_rt_stat(path, &st, &found);
    return found && S_ISDIR(st.st_mode);
}

void siyo_rt_io_eprintln(const SiyoStr *message) {
    /* System.out flushes each line; flushing here keeps 2>&1 in program order. */
    fflush(stdout);
    if (message == NULL) fputs("null", stderr);
    else siyo_rt_write_str(message, stderr);
    fputc('\n', stderr);
}

/* File.mkdirs on os, a normalised path: false when it exists; else mkdir, or
   make the parent first. */
static _Bool siyo_rt_mkdirs(char *os) {
    struct stat st;
    if (stat(os, &st) == 0) return 0;
    if (mkdir(os, 0777) == 0) return 1;
    char *slash = strrchr(os, '/');
    if (slash == NULL) return 0;
    if (slash == os) return 0;
    *slash = '\0';
    _Bool parent = siyo_rt_mkdirs(os) || stat(os, &st) == 0;
    *slash = '/';
    return parent && mkdir(os, 0777) == 0;
}

void siyo_rt_io_mkdir(const SiyoStr *s) {
    SiyoPath path;
    if (!siyo_rt_path(s, &path)) return;
    if (!path.invalid) siyo_rt_mkdirs(path.os);
}

_Bool siyo_rt_io_delete(const SiyoStr *s) {
    SiyoPath path;
    if (!siyo_rt_path(s, &path)) return 0;
    return !path.invalid && remove(path.os) == 0;
}

static int siyo_rt_compare_names(const void *a, const void *b) {
    return strcmp(*(char *const *)a, *(char *const *)b);
}

/* The names in a directory, without "." and "..", sorted by their bytes
   (File.list's order is the file system's, which varies); empty when the path
   is not a directory that can be read. */
SiyoArray *siyo_rt_io_list_dir(const SiyoStr *s) {
    SiyoPath path;
    if (!siyo_rt_path(s, &path)) return NULL;
    SiyoArray *result = siyo_rt_array_new(0);
    if (path.invalid) return result;
    DIR *dir = opendir(path.os);
    if (dir == NULL) return result;
    char **names = NULL;
    size_t count = 0, cap = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        if (count == cap) {
            cap = cap == 0 ? 16 : cap * 2;
            char **grown = siyo_rt_alloc(cap * sizeof(char *));
            if (count > 0) memcpy(grown, names, count * sizeof(char *));
            free(names);
            names = grown;
        }
        size_t len = strlen(entry->d_name);
        names[count] = siyo_rt_alloc(len + 1);
        memcpy(names[count], entry->d_name, len + 1);
        count++;
    }
    closedir(dir);
    if (count > 1) qsort(names, count, sizeof(char *), siyo_rt_compare_names);
    for (size_t i = 0; i < count; i++) {
        siyo_rt_array_append(result, siyo_rt_from_os(names[i]));
        free(names[i]);
    }
    free(names);
    return result;
}

SiyoArray *siyo_rt_os_args(void) {
    SiyoArray *args = siyo_rt_array_new(siyo_rt_argc > 1 ? siyo_rt_argc - 1 : 0);
    for (int i = 1; i < siyo_rt_argc; i++) siyo_rt_array_append(args, siyo_rt_from_os(siyo_rt_argv[i]));
    return args;
}

/* System.getenv(name), "" when unset. No variable's name holds '=' or NUL;
   in the US-ASCII encoding, nor a character past 0x7F (the JVM's decoded
   names hold U+FFFD there). */
SiyoStr *siyo_rt_os_env(const SiyoStr *name) {
    if (name == NULL) {
        siyo_rt_null_pointer();
        return NULL;
    }
    const unsigned char *p = (const unsigned char *)name->data;
    size_t n = (size_t)name->len;
    for (size_t i = 0; i < n; i++) {
        if (p[i] == '=' || p[i] == 0 || (p[i] >= 0x80 && !siyo_rt_jnu_utf8())) return siyo_rt_str_ascii("");
    }
    SiyoBytes os = {0};
    siyo_rt_to_os(&os, p, n);
    siyo_rt_bytes_byte(&os, 0);
    const char *value = getenv((const char *)os.bytes);
    free(os.bytes);
    return siyo_rt_from_os(value != NULL ? value : "");
}

/* System.getProperty("user.dir"): the working directory, decoded as the JVM does. */
SiyoStr *siyo_rt_os_cwd(void) {
    SiyoBytes buf = {0};
    siyo_rt_bytes_reserve(&buf, 4096);
    while (getcwd((char *)buf.bytes, buf.cap) == NULL) {
        if (errno != ERANGE) {
            free(buf.bytes);
            return siyo_rt_str_ascii("");
        }
        siyo_rt_bytes_reserve(&buf, buf.cap * 2);
    }
    SiyoStr *cwd = siyo_rt_from_os((const char *)buf.bytes);
    free(buf.bytes);
    return cwd;
}

/* System.exit(code): flushes stdout and stderr and ends the process; a
   pending catch does not see it. */
_Noreturn void siyo_rt_os_exit(int32_t code) {
    fflush(NULL);
    exit(code);
}
