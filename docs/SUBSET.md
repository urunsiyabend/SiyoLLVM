# The SiyoLLVM subset

SiyoLLVM compiles a subset of Siyo (as accepted by SiyoCompiler 0.9.0) to
textual LLVM IR and, with clang, to a native executable. Programs outside the
subset are rejected at compile time. SiyoLLVM never falls back to a different
meaning, and never emits code with undefined behaviour for a program it
accepts.

The reference semantics are those of `siyoc run` (the JVM backend) at
SiyoCompiler `02c226298296fb4379f7251a9dfba19b7e887f3d`. Every program in
`examples/` and `src/test/resources/native/` is checked against it byte for
byte.

## Accepted

| Area | Accepted |
|---|---|
| Types | `int` (32-bit), `long` (64-bit), `bool`, `string` |
| Literals | int, long (`5L`, or an int literal too large for `int`), `true`/`false`, strings (escapes `\n \t \r \\ \" \0 \$ \{ \}`, triple-quoted strings) |
| Variables | `mut`/`imut` locals and parameters; file-level globals |
| Arithmetic | `+ - * / %` on int and long, and on an int mixed with a long (the int widens) |
| Bitwise | `& \| ^ << >>` and unary `~` on int; `& \| ^` on bool |
| Comparison | `== != < <= > >=` on int/long (mixed allowed); `== !=` on bool and string |
| Logic | `&&`, `\|\|` (short-circuit), `!` |
| Unary | `+`, `-` on int |
| Assignment | `=`, `+= -= *= /=` |
| Control flow | `if`/`else` statements, `while`, `do … while`, `for` (three-part form), `break`, `continue`, `return` |
| Functions | top-level and nested (non-capturing) functions taking and returning the types above; recursion, mutual recursion, any declaration order |
| Output | `print(x)`, `println(x)` for every accepted type |
| Entry | file-level globals are initialised in source order, then `init()` runs, then `main()`; both optional, with no parameters and no return value |

## Rejected

Each produces `file(line, col): [llvm] <construct> is/are not supported by SiyoLLVM`
and exit status 1, with no output file. This holds anywhere in the file:
in functions that are never called, and in code after a `return`.

`float` values; string concatenation and interpolation (`"a" + b`, `"$x"`);
string ordering (`<` etc. on strings: the JVM compares UTF-16 code units);
`null`; arrays, maps, sets, indexing; `for … in`; structs, enums, sum types,
interfaces, `impl` blocks, actors (even when declared but unused);
member access; `match` and `if` expressions; lambdas, closures and nested
functions that capture locals; generic functions; `try`/`catch`/`throw`;
casts; `import` and `import java`; scope/spawn/send; every builtin other
than `print`/`println`; `main`/`init` with parameters or a return value.

## Runtime behaviour

- `int`/`long` arithmetic wraps (two's complement), as on the JVM.
- `MIN / -1` is `MIN` and `MIN % -1` is `0`, as on the JVM.
- Shift counts are masked to the low 5 bits; `>>` is arithmetic.
- Division or remainder by zero prints
  `java.lang.ArithmeticException: / by zero` to stderr and exits with
  status 1, after flushing stdout.
- Stack exhaustion prints `java.lang.StackOverflowError` to stderr and exits
  with status 1. The program runs on a thread whose stack SiyoLLVM sizes
  itself (16 MiB), and every function checks the stack pointer against a
  limit 256 KiB above the bottom of that stack, so the check does not depend
  on argv, the environment or `ulimit -s`.
- Strings are UTF-8 bytes with an explicit length: embedded `\0` is printed
  and compared like any other byte. Output is always UTF-8 bytes, whatever
  the locale (the JVM reference also writes UTF-8 under `LC_ALL=C`).
- A global read before its initialiser has run holds the JVM default
  (`0`, `0L`, `false`, or null, which prints as `null`); `==` on strings is
  value equality with `null == null`.
- `&&` and `||` evaluate their right operand only when needed.

## Deviations from `siyoc run`

| Situation | `siyoc run` (0.9.0) | SiyoLLVM |
|---|---|---|
| Uncaught `ArithmeticException` / `StackOverflowError` | exception line plus a Java stack trace | exception line only; same exit status (1) |
| Recursion depth at which stack overflow is reported | depends on the JVM thread stack (about 10 000–100 000 frames) | fixed 16 MiB program stack; usually deeper |
| stdout closed early (e.g. piped into `head`) | keeps running, exit 0 | killed by SIGPIPE (exit 141 from the shell), as C programs are |
| A `bool`/`long`/`string` function falling off its end without `return` | `VerifyError` at class load (`int` functions return 0) | returns the type's default (`false`, `0L`, null) |
| `fn main() -> int` | crashes with `NoSuchMethodError` | rejected at compile time |

## Platform

Linux with glibc on x86_64 or aarch64. The IR declares only libc functions
(`printf`, `fwrite`, `fputc`, `fputs`, `fflush`, `memcmp`, `exit`, and
`pthread_attr_init`, `pthread_attr_setstacksize`, `pthread_create`,
`pthread_join`) and leaves the target triple to clang. clang 15 or newer is required
(opaque pointers); clang 19 is what CI and the native gate use.
