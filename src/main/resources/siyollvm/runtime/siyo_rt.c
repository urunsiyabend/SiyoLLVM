/*
 * SiyoLLVM runtime (C11, libc only). See siyo_rt.h.
 */
#define _GNU_SOURCE

#include "siyo_rt.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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

/* UTF-16 length of s: one unit per sequence, two for a 4-byte one. */
static int64_t siyo_rt_str_utf16_len(const SiyoStr *s) {
    if (s->utf16_len >= 0) return s->utf16_len;
    const unsigned char *bytes = (const unsigned char *)s->data;
    int64_t units = 0;
    for (int64_t i = 0; i < s->len; i++) {
        if ((bytes[i] & 0xC0) != 0x80) units += bytes[i] >= 0xF0 ? 2 : 1;
    }
    return units;
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
