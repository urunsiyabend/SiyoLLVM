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
| Types | `int` (32-bit), `long` (64-bit), `bool`, `string`, `any` (`object`) holding one of those or null |
| Literals | int, long (`5L`, or an int literal too large for `int`), `true`/`false`, strings (escapes `\n \t \r \\ \" \0 \$ \{ \}`, triple-quoted strings), `null` |
| Variables | `mut`/`imut` locals and parameters; file-level globals |
| Arithmetic | `+ - * / %` on int and long, and on an int mixed with a long (the int widens) |
| Bitwise | `& \| ^ << >>` and unary `~` on int; `& \| ^` on bool |
| Comparison | `== != < <= > >=` on int/long (mixed allowed); `== !=` on bool; `== != < <= > >=` on string (ordering is Java's `compareTo`, over UTF-16 code units) |
| Logic | `&&`, `\|\|` (short-circuit), `!` |
| Strings | `+` joining a string with an int, long, bool or string (either side, so `1 + 2 + "x"` is `3x`); interpolation `"$x"`, `"${e}"`; `s += v`; `toString(x)` on every accepted type |
| Unary | `+`, `-` on int |
| Assignment | `=`, `+= -= *= /=` |
| Control flow | `if`/`else` statements, `while`, `do … while`, `for` (three-part form), `break`, `continue`, `return` |
| Exceptions | `throw v` of any accepted value (an int, long or bool is boxed); `error(msg)`, which throws its argument; `try { … } catch e { … }` statements, nested anywhere (in loops, in other try and catch bodies), with `break`, `continue` and `return` inside both bodies; the catch variable untyped (`any`) or typed `catch e: int` / `long` / `bool` / `string` / `any` (a checked cast, as for an `any` read); `try` expressions (`imut v = try { parseLong(s) } catch e { 0L - 1L }`) as a statement, a declaration's or assignment's value, a condition, a returned or thrown value, or the first argument of a function or `toString` (also nested: `imut s = f(g(try { … } catch e { … }))`) |
| `any` | declared as a variable, parameter or return type; an int, long, bool, string or null stored into it by declaration (`mut a: any = 5`) or argument, and a string or null also by assignment or `return`; read back into an `int`, `long`, `bool` or `string` variable, parameter or return value with a checked cast; printed, `toString`, joined with `+` to a string; `==`/`!=` against any value (Java's `Objects.equals`) |
| Functions | top-level and nested (non-capturing) functions taking and returning the types above; recursion, mutual recursion, any declaration order |
| Builtins | `print(x)`, `println(x)`, `toString(x)` for every accepted type (`any` included); on strings `len`, `substring`, `contains`, `indexOf`, `startsWith`, `endsWith`, `replace`, `trim`, `toUpper`, `toLower`, `ord`, and `chr(int)`; `parseInt`, `parseLong`, `toInt(string)`, `toInt(long)`, `toLong(int)` |
| Entry | file-level globals are initialised in source order, then `init()` runs, then `main()`; both optional, with no parameters and no return value |

## Rejected

Each produces `file(line, col): [llvm] <construct> is/are not supported by SiyoLLVM`
and exit status 1, with no output file. This holds anywhere in the file:
in functions that are never called, and in code after a `return`.

`float` values (also as an operand of `+` on strings or in `"$x"`);
arrays, maps, sets, indexing; `for … in`; structs, enums, sum types,
interfaces, `impl` blocks, actors (even when declared but unused);
member access; `match` and `if` expressions; lambdas, closures and nested
functions that capture locals; generic functions; a `try` expression
anywhere else (an operand of an operator, `x += try …`, an argument of
`print`/`println`, of a builtin other than `toString`, or after a function's
first argument: the reference evaluates it on a non-empty operand stack and
fails with `VerifyError`, except as a left operand or a string builtin's or
`error()`'s first argument, which are rejected too), or one whose body ends
in a call with no value (`try { println(x) } catch e { … }`; the reference
fails to compile it); a catch variable of a type outside the subset; casts; `import` and `import java`; scope/spawn/send; `len` of anything but
a string; every builtin not listed above, `split` included (it returns an
array); `main`/`init` with parameters or a return value.

An `any` value as an operand of arithmetic, ordering, `&&`/`||`, `!`, as a
condition (`if a`), or as an argument of a string builtin or `len`; assigning
an int, long or bool to an `any` variable after its declaration, or
returning one from a function returning `any` (the reference fails with
`VerifyError` on both).

## Runtime behaviour

- `int`/`long` arithmetic wraps (two's complement), as on the JVM.
- `MIN / -1` is `MIN` and `MIN % -1` is `0`, as on the JVM.
- Shift counts are masked to the low 5 bits; `>>` is arithmetic.
- Division or remainder by zero raises
  `java.lang.ArithmeticException: / by zero`.
- Exceptions: `throw v` and `error(v)` raise `v` itself; runtime errors raise
  the Java exception the JVM would (the messages are listed below). A raise
  goes to the innermost enclosing `catch` in the function, or else leaves the
  function and the next caller's, up to `main`; the rest of the expression
  being evaluated is skipped (later arguments, the right operand, the call
  itself). The catch variable receives the thrown value itself, or for a
  runtime error its message (`/ by zero`, `Range [2, 9) out of bounds for
  length 3`, `For input string: ""`, …), or the exception's simple class name
  when it has no message (`NullPointerException`), as the JVM's
  `SiyoRuntime.errorPayload` does. A typed catch variable that does not match
  the payload raises `ClassCastException` (or `NullPointerException`) from
  the catch block, to the enclosing handler. An exception that leaves a
  global initialiser, `init()` or `main()` is uncaught: stdout is flushed,
  stderr gets `codeanalysis.SiyoThrow: <value>` (the value as
  `String.valueOf` writes it, `null` for null) for a throw or `error()`, or
  `<java class>: <message>` for a runtime error, and the program exits with
  status 1; no later global, `init()` or `main()` runs. There is no
  `setjmp`/`longjmp`: a raise records a pending exception and returns, and
  the code checks for it after every call that can raise.
- A `try` expression's value is the last expression of the body that ran;
  a body ending in a statement (or in `throw`) gives null. The value is
  narrowed to the expression's type (that of the try body's last expression,
  else the catch body's, else `any`) as for an `any` read, after the try:
  `try { parseLong(s) } catch e { "none" }` raises `ClassCastException` to
  the enclosing handler when `s` does not parse, an int catch value widens
  to a long, and a null read into an `int` raises
  `java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()"`.
- Stack exhaustion prints `java.lang.StackOverflowError` to stderr and exits
  with status 1; it cannot be caught (see Deviations). The program runs on a thread whose stack SiyoLLVM sizes
  itself (16 MiB), and every function checks the stack pointer against a
  limit 256 KiB above the bottom of that stack, so the check does not depend
  on argv, the environment or `ulimit -s`.
- Strings are UTF-8 bytes with an explicit length: embedded `\0` is printed
  and compared like any other byte. Output is always UTF-8 bytes, whatever
  the locale (the JVM reference also writes UTF-8 under `LC_ALL=C`).
- Memory is never reclaimed during a run: strings live until the process
  exits, so no string can be used after it is freed. `s += x` and `a + b`
  append in place into a growing buffer when `a` is the newest string built
  on it, so building a string by repeated appends costs memory linear in its
  final size (100 000 appends: about 8 MB peak RSS). Other operations each
  allocate their result; a long-running program that creates many temporary
  strings grows accordingly. `substring` and the other index-based builtins
  scan non-ASCII strings from the start on every call (ASCII strings are
  indexed directly), so a per-character loop over a long non-ASCII string is
  quadratic.
- A global read before its initialiser has run holds the JVM default
  (`0`, `0L`, `false`, or null, which prints as `null`); `==` on strings is
  value equality with `null == null`. A null string reads as `null` in
  `+`, interpolation and `toString`, as on the JVM.
- `toString` and `+` write int and long in decimal and bool as
  `true`/`false`, as Java's `String.valueOf` does.
- `&&` and `||` evaluate their right operand only when needed.
- An `any` holding an int, long or bool holds it boxed, as `Integer`, `Long`
  or `Boolean` on the JVM: it prints and joins as the value would, and `==`
  is `Objects.equals`, so an `any` holding `5L` is not equal to `5`, and null
  equals only null. Reading an `any` into an `int`, `bool` or `string` needs
  a value of exactly that type; reading into a `long` also takes an int,
  which widens. Otherwise the program raises
  `java.lang.ClassCastException: class java.lang.Integer cannot be cast to class java.lang.String (java.lang.Integer and java.lang.String are in module java.base of loader 'bootstrap')`
  (with the classes involved). Null reads into a `string` as null and into an
  `int`, `long` or `bool` raises `java.lang.NullPointerException` (for `long`:
  `Cannot invoke "java.lang.Long.longValue()" because "value" is null`; for
  the literal `null` read into an `int`, as in `imut n: int = null` or
  `g(null)`: `Cannot invoke "java.lang.Integer.intValue()" because "null" is null`,
  `Boolean.booleanValue()` for `bool`).
- String lengths and indexes count UTF-16 code units, as on the JVM:
  `len("çağ😀x")` is 6. `substring` may split a surrogate pair; each half is
  then a lone surrogate (printed as `?`), and joining the halves again, with
  `+` or by `replace` removing what lies between them, gives back the
  character. `indexOf`, `contains`, `startsWith`, `endsWith` and `replace`
  match lone halves against pairs as Java does; `replace` with an empty
  target inserts the replacement between every code unit.
- `substring` out of range raises
  `java.lang.StringIndexOutOfBoundsException: Range [b, e) out of bounds for length n`;
  `ord("")` raises `java.lang.StringIndexOutOfBoundsException: Index 0 out of bounds for length 0`.
- `chr(c)` is Java's `Character.toString(int)`: one code point, so
  `chr(128512)` is `😀` (two code units) and a surrogate code point gives a
  lone surrogate; outside `0..0x10FFFF` it raises
  `java.lang.IllegalArgumentException: Not a valid Unicode code point: 0x…`.
  `ord(s)` is the first UTF-16 code unit.
- `trim` strips code units up to U+0020 (`\0` and tabs included) at both
  ends, and nothing else.
- `parseInt` and `toInt(string)` return 0 for anything `Integer.parseInt`
  rejects (empty, a lone sign, spaces, overflow, null); `parseLong` raises
  `java.lang.NumberFormatException: For input string: "…"` instead (for null:
  `Cannot parse null string`). Both accept an optional `+`/`-` and the
  Unicode decimal digits of the BMP that Java 21 accepts (`"٣٤"` is 34).
- `toInt(long)` keeps the low 32 bits; `toLong(int)` widens.
- `toUpper`/`toLower` map each code point with glibc's `towupper`/`towlower`
  in the `C.UTF-8` locale (whatever the program's locale), plus Java's
  `ß` → `SS` (toUpper) and `İ` → `i̇` (toLower). ASCII letters, lone
  surrogates and those two special cases are mapped without the locale. Any
  other non-ASCII code point needs it: if `C.UTF-8` cannot be loaded, the
  program stops with `SiyoLLVM runtime: toUpper/toLower of non-ASCII text
  needs the C.UTF-8 locale, which is not available` on stderr and exit status
  1, after flushing stdout, instead of leaving the text unmapped. (The glibc
  2.43 SiyoLLVM is tested on loads `C.UTF-8` even with `LOCPATH` at an empty
  directory, so there this path is reached only by a test build of the
  runtime; see `RuntimeBuildNativeTest`.)
- A null string (a global read before its initialiser) passed to a string
  builtin or ordered with `<` etc. raises `java.lang.NullPointerException`.

## Deviations from `siyoc run`

| Situation | `siyoc run` (0.9.0) | SiyoLLVM |
|---|---|---|
| Uncaught exception (`throw`, `error()`, any runtime error) | exception line plus a Java stack trace | exception line only; same exit status (1) |
| `StackOverflowError` inside `try` | caught; the catch variable is `"StackOverflowError"` | not catchable: the program stops as for an uncaught one |
| Recursion depth at which stack overflow is reported | depends on the JVM thread stack (about 10 000–100 000 frames) | fixed 16 MiB program stack; usually deeper |
| stdout closed early (e.g. piped into `head`) | keeps running, exit 0 | killed by SIGPIPE (exit 141 from the shell), as C programs are |
| A `bool`/`long`/`string` function falling off its end without `return` | `VerifyError` at class load (`int` functions return 0) | returns the type's default (`false`, `0L`, null) |
| `fn main() -> int` | crashes with `NoSuchMethodError` | rejected at compile time |
| Null `any` (other than the literal `null`) read into an `int` or `bool` | `java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because the return value of "<Class>.f(Object)" is null` (the helpful message names where the null came from: a call, `"<local1>"`, a field) | `java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()"` (`Boolean.booleanValue()` for bool), without the `because …` part; a catch variable receives that shorter message |
| String builtin or ordering on a null string | `java.lang.NullPointerException: Cannot invoke "String.length()" because "<Class>.<global>" is null` (a helpful message naming JVM internals) | `java.lang.NullPointerException`; a catch variable receives `NullPointerException` instead of the helpful message |
| `toUpper` of characters whose Java upper case is several characters, other than `ß` | one-to-many mapping from Unicode SpecialCasing | the character is kept, or mapped to a single character (see below) |
| `toLower` of `Σ` ending a word | final `ς` | `σ` |
| `toUpper`/`toLower` of characters added after Unicode 15.0 | unchanged (Java 21 knows Unicode 15.0) | mapped by glibc's newer tables (glibc 2.43 measured) |

**Case mapping, measured.** Every code point was mapped singly by Java 21
(`String.toUpperCase()`/`toLowerCase()`, default locale `en_US` under
`LC_ALL=C`) and by the runtime's rule with glibc 2.43. They differ only here:

- Java's one-to-many upper case, which SiyoLLVM does not produce: U+0149 `ŉ`,
  U+01F0 `ǰ`, U+0390 `ΐ`, U+03B0 `ΰ`, U+0587 `և`, U+1E96–U+1E9A,
  U+1F50, U+1F52, U+1F54, U+1F56, U+1F80–U+1FAF (Greek with ypogegrammeni;
  glibc maps the lower-case ones to their single title-case letter
  U+1F88–U+1FAF), U+1FB2–U+1FB4, U+1FB6, U+1FB7, U+1FBC, U+1FC2–U+1FC4,
  U+1FC6, U+1FC7, U+1FCC, U+1FD2, U+1FD3, U+1FD6, U+1FD7, U+1FE2–U+1FE4,
  U+1FE6, U+1FE7, U+1FF2–U+1FF4, U+1FF6, U+1FF7, U+1FFC, the ligatures
  U+FB00–U+FB06 and U+FB13–U+FB17. (U+1FB3/U+1FC3/U+1FF3 upper-case to
  U+1FBC/U+1FCC/U+1FFC.)
- Pairs only glibc knows (Unicode 16 and later): U+019B/U+A7DC,
  U+0264/U+A7CB, U+1C89/U+1C8A, U+A7CC–U+A7CF, U+A7D2–U+A7D5, U+A7DA/U+A7DB,
  U+10D50–U+10D65/U+10D70–U+10D85, U+16EA0–U+16EB8/U+16EBB–U+16ED3. Another
  glibc version may differ here.

The context-dependent final sigma cannot show up in a per-character
comparison and is listed separately above. `case_mapping` in the corpus uses
none of these characters.

## Platform

Linux with glibc on x86_64 or aarch64. The IR declares only functions of the
SiyoLLVM C runtime (`siyo_rt_*`), which clang compiles from C11 source shipped
in the jar and links with the program; the runtime uses libc (including
pthreads) only. The IR leaves the target triple to clang. clang 15 or newer is required
(opaque pointers); clang 19 is what CI and the native gate use.
