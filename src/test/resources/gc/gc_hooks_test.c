/*
 * C-level collector tests (RuntimeGcNativeTest). Linked with siyo_rt.c, which
 * runs siyo_program() on its program thread, as for a compiled Siyo program.
 *
 * A block the test wants reclaimed must not be reachable from anything the
 * collector scans, so its address is kept hidden (XOR-ed with MASK), made
 * and linked only inside noinline helpers (which hold the raw pointers in
 * locals while they build, since any allocation may collect), and the stack below the test
 * frame is scrubbed before collecting: then the only copies of the address
 * are the ones the test deliberately leaves (in a block, a global, a
 * register, the pending exception).
 *
 * Prints "ok <name>" or "FAIL <name>" per check, then "done <failures>";
 * exits 1 when anything failed.
 */
#define _GNU_SOURCE
#include "siyo_rt.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MASK ((uintptr_t)0x5A5A5A5A5A5A5A5Aull)
#define FILL UINT64_C(0x1122334455667788)

static int failures;

static void check(int ok, const char *name) {
    printf("%s %s\n", ok ? "ok" : "FAIL", name);
    fflush(stdout);
    if (!ok) failures++;
}

static void *reveal(uintptr_t hidden) {
    return (void *)(hidden ^ MASK);
}

/* Overwrites the stack below the caller's frame, where dead helper frames left addresses. */
__attribute__((noinline))
static void scrub(void) {
    volatile unsigned char junk[256 * 1024];
    for (size_t i = 0; i < sizeof junk; i++) junk[i] = 0;
}

/* A new block of size bytes (SCAN or NOSCAN) whose first word is fill; returns its hidden address. */
__attribute__((noinline))
static uintptr_t hidden_block(size_t size, int scan, uint64_t fill) {
    uint64_t *p = scan ? siyo_rt_alloc(size) : siyo_rt_alloc_noscan(size);
    p[0] = fill;
    return (uintptr_t)p ^ MASK;
}

/* Whether the block behind hidden is still allocated and still starts with fill. */
__attribute__((noinline))
static int alive_with(uintptr_t hidden, uint64_t fill) {
    void *p = reveal(hidden);
    return siyo_rt_gc_block_of(p) == p && *(uint64_t *)p == fill;
}

__attribute__((noinline))
static int reclaimed(uintptr_t hidden) {
    return siyo_rt_gc_block_of(reveal(hidden)) == NULL;
}

__attribute__((noinline))
static void collect(void) {
    scrub();
    siyo_rt_gc_collect();
}

static uint64_t live_blocks(void) {
    uint64_t blocks, bytes;
    siyo_rt_gc_counts(&blocks, &bytes);
    return blocks;
}

static uint64_t live_bytes(void) {
    uint64_t blocks, bytes;
    siyo_rt_gc_counts(&blocks, &bytes);
    return bytes;
}

static int static_word;
static void *volatile global_root;

static void interior_pointers(void) {
    char *p = siyo_rt_alloc(100);
    check(siyo_rt_gc_block_of(p) == p, "interior: start of a small block");
    check(siyo_rt_gc_block_of(p + 57) == p, "interior: middle of a small block");
    check(siyo_rt_gc_block_of(p + 99) == p, "interior: last requested byte");
    int local = 0;
    check(siyo_rt_gc_block_of(&local) == NULL, "interior: stack address is not heap");
    check(siyo_rt_gc_block_of(&static_word) == NULL, "interior: static address is not heap");
    void *m = malloc(64);
    check(siyo_rt_gc_block_of(m) == NULL, "interior: malloc memory is not heap");
    free(m);
    check(siyo_rt_gc_block_of(NULL) == NULL, "interior: NULL is not heap");
    check(siyo_rt_gc_block_of("literal") == NULL, "interior: a constant is not heap");

    size_t big = 3 * 65536 + 5;
    char *q = siyo_rt_alloc(big);
    check(siyo_rt_gc_block_of(q) == q, "interior: start of a large block");
    check(siyo_rt_gc_block_of(q + 2 * 65536 + 100) == q, "interior: third 64 KiB of a large block");
    check(siyo_rt_gc_block_of(q + big - 1) == q, "interior: last byte of a large block");

    /* A string's data pointer points into its SiyoBuf, past the buffer's header. */
    SiyoStr *s = siyo_rt_str_concat(siyo_rt_str_from_int(12345), siyo_rt_str_from_int(678));
    check(s->buf != NULL && siyo_rt_gc_block_of(s->data) == s->buf, "interior: string data resolves to its buffer");
    check(siyo_rt_gc_block_of(s->data + 3) == s->buf, "interior: string bytes resolve to their buffer");
    /* Array items: an item pointer resolves to the items block. */
    SiyoArray *a = siyo_rt_range(0, 300);
    check(siyo_rt_gc_block_of(&a->items[250]) == (void *)a->items, "interior: item pointer resolves to items block");
    collect();
    check(s->len == 8 && memcmp(s->data, "12345678", 8) == 0, "interior: string survives collection via locals");
    check(a->len == 300 && siyo_rt_unbox_int(a->items[299]) == 299, "interior: array survives collection");
    check(p[0] == 0 && siyo_rt_gc_block_of(p) == p && siyo_rt_gc_block_of(q) == q, "interior: blocks held by locals survive");
}

__attribute__((noinline))
static void dirty_garbage(int count) {
    for (int i = 0; i < count; i++) {
        unsigned char *p = siyo_rt_alloc_noscan(200);
        memset(p, 0xAB, 200);
    }
}

static void zeroing(void) {
    dirty_garbage(2000);
    collect();
    int all_zero = 1;
    for (int i = 0; i < 2000; i++) {
        unsigned char *p = siyo_rt_alloc(200);
        for (int k = 0; k < 200; k++) all_zero &= p[k] == 0;
    }
    check(all_zero, "zeroing: blocks reused after collection come back zeroed");
}

__attribute__((noinline))
static void garbage(int count, size_t size) {
    for (int i = 0; i < count; i++) hidden_block(size, i % 2, FILL);
}

static void unreachable_reclaimed(void) {
    collect();
    uint64_t before = live_blocks();
    garbage(5000, 48);
    check(live_blocks() >= before + 5000, "reclaim: 5000 blocks allocated");
    uint64_t collections = siyo_rt_gc_collections();
    collect();
    check(siyo_rt_gc_collections() == collections + 1, "reclaim: a collection is counted");
    check(live_blocks() <= before + 8, "reclaim: unreachable blocks are freed");
}

/* A SCAN and a NOSCAN holder, each pointing (word 3) at a SCAN block. */
__attribute__((noinline))
static void make_holders(uintptr_t *scan_holder, uintptr_t *noscan_holder, uintptr_t *kept, uintptr_t *dropped) {
    void **scan = siyo_rt_alloc(64);
    void **noscan = siyo_rt_alloc_noscan(64);
    uint64_t *a = siyo_rt_alloc(32);
    uint64_t *b = siyo_rt_alloc(32);
    a[0] = FILL;
    b[0] = FILL + 1;
    scan[3] = a;
    noscan[3] = b;
    *scan_holder = (uintptr_t)scan ^ MASK;
    *noscan_holder = (uintptr_t)noscan ^ MASK;
    *kept = (uintptr_t)a ^ MASK;
    *dropped = (uintptr_t)b ^ MASK;
}

static void scan_and_noscan(void) {
    uintptr_t scan_holder, noscan_holder, kept, dropped;
    make_holders(&scan_holder, &noscan_holder, &kept, &dropped);
    global_root = reveal(scan_holder);
    void *volatile noscan_root = reveal(noscan_holder);
    collect();
    check(alive_with(kept, FILL), "scan: a SCAN block's words keep their target");
    check(reclaimed(dropped), "noscan: a NOSCAN block's words keep nothing");
    check(siyo_rt_gc_block_of(noscan_root) == noscan_root, "noscan: the NOSCAN block itself is kept by its root");
    global_root = NULL;
    noscan_root = NULL;
    collect();
    check(reclaimed(kept), "scan: the target goes once its holder is unreachable");
}

/* a <-> b, plus a block that holds itself (reachable from a); returns a hidden. */
__attribute__((noinline))
static uintptr_t make_cycle(uintptr_t *b_out, uintptr_t *self_out) {
    void **a = siyo_rt_alloc(32);
    void **b = siyo_rt_alloc(32);
    void **self = siyo_rt_alloc(32);
    a[0] = (void *)(uintptr_t)FILL;
    b[0] = (void *)(uintptr_t)(FILL + 2);
    self[0] = (void *)(uintptr_t)(FILL + 3);
    a[1] = b;
    b[1] = a;
    self[1] = self;
    a[2] = self;
    *b_out = (uintptr_t)b ^ MASK;
    *self_out = (uintptr_t)self ^ MASK;
    return (uintptr_t)a ^ MASK;
}

static void cycles(void) {
    uintptr_t b, self;
    uintptr_t a = make_cycle(&b, &self);
    global_root = reveal(b);
    collect();
    check(alive_with(a, FILL) && alive_with(b, FILL + 2) && alive_with(self, FILL + 3),
            "cycles: kept while an alias (a global) lives");
    global_root = NULL;
    collect();
    check(reclaimed(a) && reclaimed(b) && reclaimed(self), "cycles: reclaimed once unreachable");
}

/* An 8 MiB SCAN block pointing, 4 MiB in, at a 5000-byte NOSCAN block; returns the big one hidden. */
__attribute__((noinline))
static uintptr_t make_big(uintptr_t *child_out) {
    uint64_t *big = siyo_rt_alloc((size_t)8 << 20);
    uint64_t *child = siyo_rt_alloc_noscan(5000);
    big[0] = FILL;
    child[0] = FILL + 4;
    big[(size_t)1 << 19] = (uint64_t)(uintptr_t)child;
    *child_out = (uintptr_t)child ^ MASK;
    return (uintptr_t)big ^ MASK;
}

static void large_blocks(void) {
    collect();
    uint64_t before = live_bytes();
    uintptr_t child;
    uintptr_t big = make_big(&child);
    check(live_bytes() >= before + ((uint64_t)8 << 20), "large: counted in heap bytes");
    global_root = reveal(big);
    collect();
    check(alive_with(big, FILL) && alive_with(child, FILL + 4), "large: kept, with what it points to");
    global_root = NULL;
    collect();
    check(reclaimed(big) && reclaimed(child), "large: reclaimed once unreachable");
    check(live_bytes() < before + 65536, "large: heap bytes drop back");
}

/* A linked list of n blocks, deeper than any C stack could recurse; returns its hidden head. */
__attribute__((noinline))
static uintptr_t make_list(int n) {
    void **head = NULL;
    for (int i = 0; i < n; i++) {
        void **node = siyo_rt_alloc(16);
        node[0] = head;
        node[1] = (void *)(uintptr_t)(i + 1);
        head = node;
    }
    return (uintptr_t)head ^ MASK;
}

static void deep_structure(void) {
    uintptr_t list = make_list(1000000);
    global_root = reveal(list);
    collect();
    long count = 0;
    for (void **node = global_root; node != NULL; node = node[0]) {
        if ((uintptr_t)node[1] != (uintptr_t)(1000000 - count)) break;
        count++;
    }
    check(count == 1000000, "deep: a million-node list survives intact (explicit mark stack)");
    global_root = NULL;
    collect();
    check(reclaimed(list), "deep: and is reclaimed when dropped");
}

#if defined(__x86_64__)
/* hold_in_register(real, collect_fn): r12 = real, call collect_fn, return r12.
   The block's address then exists only in a callee-saved register. */
__asm__(".text\n"
        ".p2align 4\n"
        "siyo_test_hold_in_register:\n"
        "    pushq %r12\n"
        "    movq %rdi, %r12\n"
        "    xorq %rdi, %rdi\n"
        "    callq *%rsi\n"
        "    movq %r12, %rax\n"
        "    popq %r12\n"
        "    retq\n");
#elif defined(__aarch64__)
__asm__(".text\n"
        ".p2align 4\n"
        "siyo_test_hold_in_register:\n"
        "    stp x19, x30, [sp, #-16]!\n"
        "    mov x19, x0\n"
        "    mov x0, xzr\n"
        "    blr x1\n"
        "    mov x0, x19\n"
        "    ldp x19, x30, [sp], #16\n"
        "    ret\n");
#else
#error "register root test: unsupported architecture"
#endif
void *siyo_test_hold_in_register(void *value, void (*collect_fn)(void));

__attribute__((noinline))
static uintptr_t hold_revealed_in_register(uintptr_t hidden) {
    return (uintptr_t)siyo_test_hold_in_register(reveal(hidden), collect) ^ MASK;
}

__attribute__((noinline))
static uintptr_t hold_hidden_in_register(uintptr_t hidden) {
    return (uintptr_t)siyo_test_hold_in_register((void *)hidden, collect);
}

static void register_roots(void) {
    uintptr_t held = hidden_block(48, 1, FILL + 5);
    uintptr_t back = hold_revealed_in_register(held);
    check(back == held && alive_with(held, FILL + 5), "registers: a block held only in a callee-saved register survives");
    uintptr_t control = hidden_block(48, 1, FILL + 6);
    hold_hidden_in_register(control);
    check(reclaimed(control), "registers: control (only the hidden value in the register) is reclaimed");
}

__attribute__((noinline))
static void throw_hidden(uintptr_t hidden) {
    siyo_rt_throw(reveal(hidden));
}

static void pending_exception_root(void) {
    uintptr_t payload = hidden_block(64, 1, FILL + 7);
    throw_hidden(payload);
    collect();
    check(alive_with(payload, FILL + 7), "tls: the pending exception's payload survives a collection");
    check(siyo_rt_pending_flag == 1, "tls: still pending");
    void *caught = siyo_rt_catch();
    check((uintptr_t)caught == (payload ^ MASK) && *(uint64_t *)caught == FILL + 7, "tls: catch returns it intact");
    caught = NULL;

    /* A Java exception's message (a heap string) is a root too. */
    siyo_rt_str_substring(siyo_rt_str_from_int(42), 5, 1);
    collect();
    SiyoStr *message = siyo_rt_catch();
    const char *expected = "Range [5, 1) out of bounds for length 2";
    check(message != NULL && message->len == (int64_t)strlen(expected)
            && memcmp(message->data, expected, strlen(expected)) == 0,
            "tls: a raised exception's message survives a collection");
}

static void box_cache(void) {
    void *small = siyo_rt_box_int(7);
    check(siyo_rt_gc_block_of(small) == NULL, "boxes: the small-int cache is static, not heap");
    void *big = siyo_rt_box_int(100000);
    check(siyo_rt_gc_block_of(big) == big, "boxes: other ints are heap boxes");
    collect();
    check(siyo_rt_unbox_int(small) == 7 && siyo_rt_unbox_int(big) == 100000, "boxes: survive collection");
}

void siyo_program(void) {
    interior_pointers();
    zeroing();
    unreachable_reclaimed();
    scan_and_noscan();
    cycles();
    large_blocks();
    deep_structure();
    register_roots();
    pending_exception_root();
    box_cache();
    printf("done %d\n", failures);
    fflush(stdout);
    if (failures != 0) exit(1);
}
