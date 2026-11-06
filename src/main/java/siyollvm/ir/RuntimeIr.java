package siyollvm.ir;

/**
 * The fixed part of every module: libc declarations, constants and helper
 * functions written directly in IR. No C is compiled; the program links
 * against libc only.
 *
 * <p>A Siyo string is a pointer to {@code { i64 length, [length x i8] bytes }}
 * holding UTF-8, or null for Siyo's null. Bytes start at offset 8.
 */
final class RuntimeIr {
    private RuntimeIr() {}

    /**
     * The program runs on a thread with a stack of exactly this size, so the
     * guard's budget does not depend on argv, the environment or ulimit -s.
     * The headroom below the limit is left for libc calls.
     */
    static final long PROGRAM_STACK_SIZE = 16L * 1024 * 1024;
    static final long STACK_HEADROOM = 256L * 1024;

    /** First line siyoc prints for the matching uncaught JVM error. */
    static final String DIV_BY_ZERO_MESSAGE = "java.lang.ArithmeticException: / by zero\n";
    static final String STACK_OVERFLOW_MESSAGE = "java.lang.StackOverflowError\n";
    static final String THREAD_MESSAGE = "SiyoLLVM runtime: cannot start the program thread\n";

    static String text() {
        return """
                declare i32 @printf(ptr, ...)
                declare i64 @fwrite(ptr, i64, i64, ptr)
                declare i32 @fputc(i32, ptr)
                declare i32 @fputs(ptr, ptr)
                declare i32 @fflush(ptr)
                declare i32 @memcmp(ptr, ptr, i64)
                declare i32 @pthread_attr_init(ptr)
                declare i32 @pthread_attr_setstacksize(ptr, i64)
                declare i32 @pthread_create(ptr, ptr, ptr, ptr)
                declare i32 @pthread_join(i64, ptr)
                declare void @exit(i32) noreturn

                @stdout = external global ptr
                @stderr = external global ptr

                @siyo.fmt.int = private unnamed_addr constant [3 x i8] c"%d\\00"
                @siyo.fmt.long = private unnamed_addr constant [5 x i8] c"%lld\\00"
                @siyo.lit.true = private unnamed_addr constant { i64, [4 x i8] } { i64 4, [4 x i8] c"true" }, align 8
                @siyo.lit.false = private unnamed_addr constant { i64, [5 x i8] } { i64 5, [5 x i8] c"false" }, align 8
                @siyo.lit.null = private unnamed_addr constant { i64, [4 x i8] } { i64 4, [4 x i8] c"null" }, align 8
                @siyo.msg.div_by_zero = private unnamed_addr constant %DIVLEN% c"%DIVMSG%"
                @siyo.msg.stack_overflow = private unnamed_addr constant %SOLEN% c"%SOMSG%"
                @siyo.msg.thread = private unnamed_addr constant %THLEN% c"%THMSG%"

                ; Lowest stack address a Siyo function may start at; set by @siyo.stack_init.
                @siyo.stack_limit = internal global i64 0

                define internal void @siyo.print_str(ptr %s) {
                entry:
                  %isnull = icmp eq ptr %s, null
                  %str = select i1 %isnull, ptr @siyo.lit.null, ptr %s
                  %len = load i64, ptr %str, align 8
                  %data = getelementptr inbounds i8, ptr %str, i64 8
                  %out = load ptr, ptr @stdout
                  %w = call i64 @fwrite(ptr %data, i64 1, i64 %len, ptr %out)
                  ret void
                }

                define internal void @siyo.print_bool(i1 %b) {
                entry:
                  %s = select i1 %b, ptr @siyo.lit.true, ptr @siyo.lit.false
                  call void @siyo.print_str(ptr %s)
                  ret void
                }

                define internal void @siyo.print_int(i32 %v) {
                entry:
                  %r = call i32 (ptr, ...) @printf(ptr @siyo.fmt.int, i32 %v)
                  ret void
                }

                define internal void @siyo.print_long(i64 %v) {
                entry:
                  %r = call i32 (ptr, ...) @printf(ptr @siyo.fmt.long, i64 %v)
                  ret void
                }

                define internal void @siyo.print_newline() {
                entry:
                  %out = load ptr, ptr @stdout
                  %r = call i32 @fputc(i32 10, ptr %out)
                  ret void
                }

                ; Value equality, as String.equals with null == null: same length, same bytes.
                define internal i1 @siyo.str_eq(ptr %a, ptr %b) {
                entry:
                  %same = icmp eq ptr %a, %b
                  br i1 %same, label %yes, label %nulls
                nulls:
                  %anull = icmp eq ptr %a, null
                  %bnull = icmp eq ptr %b, null
                  %either = or i1 %anull, %bnull
                  br i1 %either, label %no, label %lengths
                lengths:
                  %la = load i64, ptr %a, align 8
                  %lb = load i64, ptr %b, align 8
                  %samelen = icmp eq i64 %la, %lb
                  br i1 %samelen, label %bytes, label %no
                bytes:
                  %da = getelementptr inbounds i8, ptr %a, i64 8
                  %db = getelementptr inbounds i8, ptr %b, i64 8
                  %c = call i32 @memcmp(ptr %da, ptr %db, i64 %la)
                  %r = icmp eq i32 %c, 0
                  ret i1 %r
                yes:
                  ret i1 true
                no:
                  ret i1 false
                }

                define internal void @siyo.fail(ptr %message) noreturn {
                entry:
                  %f = call i32 @fflush(ptr null)
                  %err = load ptr, ptr @stderr
                  %r = call i32 @fputs(ptr %message, ptr %err)
                  call void @exit(i32 1)
                  unreachable
                }

                define internal void @siyo.div_by_zero() noreturn cold {
                entry:
                  call void @siyo.fail(ptr @siyo.msg.div_by_zero)
                  unreachable
                }

                define internal void @siyo.stack_overflow() noreturn cold {
                entry:
                  call void @siyo.fail(ptr @siyo.msg.stack_overflow)
                  unreachable
                }

                ; Called first on the program thread: limit = here - stack size + headroom.
                define internal void @siyo.stack_init(ptr %marker) {
                entry:
                  %here = ptrtoint ptr %marker to i64
                  %low = sub i64 %here, %STACK%
                  %limit = add i64 %low, %HEADROOM%
                  store i64 %limit, ptr @siyo.stack_limit
                  ret void
                }

                ; Starts @siyo.program on a thread with a known stack size and waits for it.
                ; The program ends the process itself on a runtime error.
                define internal void @siyo.run_program_thread() {
                entry:
                  %attr = alloca [64 x i64], align 16
                  %thread = alloca i64, align 8
                  %a = call i32 @pthread_attr_init(ptr %attr)
                  %s = call i32 @pthread_attr_setstacksize(ptr %attr, i64 %STACK%)
                  %c = call i32 @pthread_create(ptr %thread, ptr %attr, ptr @siyo.program, ptr null)
                  %failed = or i32 %s, %c
                  %bad = icmp ne i32 %failed, 0
                  br i1 %bad, label %fail, label %join
                fail:
                  call void @siyo.fail(ptr @siyo.msg.thread)
                  unreachable
                join:
                  %t = load i64, ptr %thread, align 8
                  %j = call i32 @pthread_join(i64 %t, ptr null)
                  ret void
                }
                """
                .replace("%DIVLEN%", cStringType(DIV_BY_ZERO_MESSAGE))
                .replace("%DIVMSG%", cStringBody(DIV_BY_ZERO_MESSAGE))
                .replace("%SOLEN%", cStringType(STACK_OVERFLOW_MESSAGE))
                .replace("%SOMSG%", cStringBody(STACK_OVERFLOW_MESSAGE))
                .replace("%THLEN%", cStringType(THREAD_MESSAGE))
                .replace("%THMSG%", cStringBody(THREAD_MESSAGE))
                .replace("%STACK%", Long.toString(PROGRAM_STACK_SIZE))
                .replace("%HEADROOM%", Long.toString(STACK_HEADROOM));
    }

    private static String cStringType(String ascii) {
        return "[" + (ascii.length() + 1) + " x i8]";
    }

    private static String cStringBody(String ascii) {
        return StringConstants.escape(ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) + "\\00";
    }
}
