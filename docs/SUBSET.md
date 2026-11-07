# The SiyoLLVM subset

SiyoLLVM compiles a subset of Siyo (as accepted by SiyoCompiler 0.9.0) to
textual LLVM IR and, with clang, to a native executable. Programs outside the
subset are rejected at compile time. SiyoLLVM never falls back to a different
meaning, and never emits code with undefined behaviour for a program it
accepts.

The reference semantics are those of `siyoc run` (the JVM backend) at
SiyoCompiler `02c226298296fb4379f7251a9dfba19b7e887f3d`. Every program in
`examples/` and `src/test/resources/native/`, and every multi-file program in
`src/test/resources/modules/` (a directory per case, run from its `main.siyo`), is checked against it byte for
byte.

## Accepted

| Area | Accepted |
|---|---|
| Types | `int` (32-bit), `long` (64-bit), `bool`, `string`, arrays of these (`int[]`, `Array<string>`, `any[]`, and nested arrays written as unannotated literals such as `[[1, 2], [3]]`), structs, maps (`Map<K, V>`, `map`), `any` (`object`) holding one of those or null |
| Literals | int, long (`5L`, or an int literal too large for `int`), `true`/`false`, strings (escapes `\n \t \r \\ \" \0 \$ \{ \}`, triple-quoted strings), `null` |
| Variables | `mut`/`imut` locals and parameters; file-level globals |
| Arithmetic | `+ - * / %` on int and long, and on an int mixed with a long (the int widens) |
| Bitwise | `& \| ^ << >>` and unary `~` on int; `& \| ^` on bool |
| Comparison | `== != < <= > >=` on int/long (mixed allowed); `== !=` on bool; `== != < <= > >=` on string (ordering is Java's `compareTo`, over UTF-16 code units) |
| Logic | `&&`, `\|\|` (short-circuit), `!` |
| Strings | `+` joining a string with an int, long, bool or string (either side, so `1 + 2 + "x"` is `3x`); interpolation `"$x"`, `"${e}"`; `s += v`; `toString(x)` on every accepted type; `s[i]` (Java's `charAt`, as a one-unit string) |
| Unary | `+`, `-` on int |
| Assignment | `=`, `+= -= *= /=` |
| Control flow | `if`/`else` statements, `while`, `do … while`, `for` (three-part form), `for x in …` over an array (including `range(a, b)` and `split(s, d)`), a string (its UTF-16 units) or a map (its keys), `break`, `continue`, `return` |
| Exceptions | `throw v` of any accepted value (an int, long or bool is boxed); `error(msg)`, which throws its argument; `try { … } catch e { … }` statements, nested anywhere (in loops, in other try and catch bodies), with `break`, `continue` and `return` inside both bodies; the catch variable untyped (`any`) or typed `catch e: int` / `long` / `bool` / `string` / `any` (a checked cast, as for an `any` read); `try` expressions (`imut v = try { parseLong(s) } catch e { 0L - 1L }`) as a statement, a declaration's or assignment's value, a condition, a returned or thrown value, or the first argument of a function or `toString` (also nested: `imut s = f(g(try { … } catch e { … }))`) |
| Arrays | literals (`[1, 2]`, `["a"]`, `[[1], [2]]`; an empty literal `[]` typed by its declaration, `mut xs: int[] = []`); as variables, globals, parameters, return values, elements of other arrays, `any` values, thrown values, catch variables (`catch e: int[]`) and `try` expression values; `xs[i]` (also on an `any` holding an array, as the JVM backend's checkcast to `List`), `xs[i] = v` and `xs[i] += v`; `len(xs)`, `push(xs, v)`, `pop(xs)`, `removeAt(xs, i)` and their method forms (`xs.push(v)`); printing, `toString`, interpolation (`"$xs"`), `+` with a string when the array is held in an `any`; `==`/`!=` between an `any` holding an array and any value (`List.equals`); `range(a, b)` and `split(s, d)`, which make new arrays; `for x in xs` (the binder's desugaring into `len` and indexing) |
| Structs | `struct` declarations; literals (`Point { y: 2, x: 1 }`, fields in any order, some left out); field reads `p.x` (also nested, `b.p.x`, and on array elements, `ps[0].x`), writes `p.x = v` and compound writes `p.x += v`; struct values as variables, globals, parameters, return values, array elements, fields of other structs, `any` values, thrown values and catch variables (`catch e: Point`); printing, `toString`, interpolation; `==`/`!=` against `null` or an `any` (between two values of struct type it is a bind error on the reference, `Binary operator '==' is not defined for types <class codeanalysis.SiyoStruct> and <class codeanalysis.SiyoStruct>`, and so here too; hold one in an `any` to compare); fields of the types above; `typeName(x)`; a field write used as a value (`println(p.x = 5)` prints 5) |
| Maps | literals `{"a": 1, 2: true}` with keys and values of every accepted type, `{}` typed by its declaration (`mut m: Map<string, int> = {}`), and `map()`; as variables, globals, parameters, return values, array elements, struct fields, map values (`Map<string, Map<string, int>>`), `any` values (read back with a checked cast to `codeanalysis.SiyoMap`) and thrown values; `m[k]` (null for a missing key), `m[k] = v`, `m[k] += v`; the methods `m.get(k)`, `m.set(k, v)`, `m.has(k)`, `m.remove(k)`, `m.size()`, `m.keys()`; `len(m)`; `for k in m` (over a snapshot of the keys, `$mapKeys`); printing, `toString`, interpolation; `==`/`!=` between an `any` and a map (identity, as `SiyoMap` does not override `equals`; between two values of map type it is a bind error on the reference); `typeName(m)` is `""`. A read through a map whose value type is not declared (`imut m = {"a": [1]}`) is an `any`, so `push(m["a"], 2)` and `m["a"] + 1` are rejected as for any other `any`; declare the map's type (`Map<string, int[]>`) |
| `impl` methods | `impl Point { … }` blocks: instance methods (first parameter `self`) called as `p.m(a)` (also on array elements, `ps[0].m()`, and on call results, `Point.new(1, 2).m()`), static methods called as `Point.new(1, 2)`; each is a function named `Point.m` taking `self` first, with the parameter and return types above; methods calling each other on `self`, recursion, throwing to the caller. Method calls work only where the binder can tell which struct the receiver is: a variable initialised from a call or a literal, a field, an array element, a for-in variable or a catch variable (`catch e: Point`). Where it cannot (`mut q: Point = null` then `q = mk()`, or `mut q: Point = a` with `a: any`) the call binds as Java dynamic dispatch and is rejected with `method calls on a struct whose type the binder cannot tell are not supported` |
| Enums | `enum Color { Red, Green = 5, Blue }`: each member is an `int` constant (`Color.Red` is 0; a member without a value is the previous one plus 1, so `Blue` is 6; negative values allowed), usable wherever an int is: comparisons, conditions, arithmetic, arrays, struct fields, `any` values. An enum's name is not a type on the reference (`c: Color` is a bind error, `Type 'Color' does not exist`), nor is it a value (`Color` alone: `Name 'Color' does not exist`); `typeName(Color.Red)` is `""` |
| `any` | declared as a variable, parameter or return type; an int, long, bool, string or null stored into it by declaration (`mut a: any = 5`) or argument, and a string or null also by assignment or `return`; read back into an `int`, `long`, `bool` or `string` variable, parameter or return value with a checked cast; printed, `toString`, joined with `+` to a string; `==`/`!=` against any value (Java's `Objects.equals`) |
| Functions | top-level and nested (non-capturing) functions taking and returning the types above; recursion, mutual recursion, any declaration order |
| Builtins | `print(x)`, `println(x)`, `toString(x)` for every accepted type (`any`, arrays, structs and maps included); `typeName(x)`; `len`, `push`, `pop`, `removeAt` on arrays; `len` and `map()` for maps; `range(a, b)`; on strings `len`, `s[i]`, `split`, `substring`, `contains`, `indexOf`, `startsWith`, `endsWith`, `replace`, `trim`, `toUpper`, `toLower`, `ord`, and `chr(int)`; `parseInt`, `parseLong`, `toInt(string)`, `toInt(long)`, `toLong(int)` |
| Modules | `import "x"`, `import "lib/x"`, `import "x" as y` of user modules, resolved by the binder exactly as `siyoc` resolves them, from the main file's directory: `x.siyo` beside the importing file, then `x/index.siyo`, `x/x.siyo`, then the working directory. Each module's functions (`x.f()`, or `y.f()` under an alias), globals (`x.n`, read and written), structs, enums and `impl` methods (by their bare names, `Point.new(1, 2)`) are usable by the importer. A module imported several times, by several files or under several names, is one module with one set of globals. Two modules may declare functions and globals of the same name; each call reaches its own module's function. Code in a module is checked against the subset like the main file's, and diagnostics in it name it relative to the main file's directory (`lib/util.siyo(3, 11): …`); a missing module is the binder's diagnostic at the importing `import` |
| Entry | each imported module, dependencies first (the binder's registry order, as the JVM's class initialisation runs them), has its file-level globals initialised and then its own `init()` run; then the main file's globals are initialised in source order, then its `init()` runs, then `main()`; `init` and `main` optional, with no parameters and no return value. An exception escaping a module's globals or `init()` ends the program before `main` with `java.lang.ExceptionInInitializerError` and exit status 1, as the JVM's failed class initialiser does |

## Rejected

Each produces `file(line, col): [llvm] <construct> is/are not supported by SiyoLLVM`
and exit status 1, with no output file. This holds anywhere in the file:
in functions that are never called, and in code after a `return`.

`float` values (also as an operand of `+` on strings or in `"$x"`);
sets (`#{…}`, `set()`, `for … in` over a set); the `SiyoMap` methods other than
`get`, `set`, `has`, `remove`, `size` and `keys` (`values`, `increment`,
`getInt`, `getLong`, `getStr`, `getBool`: `map method <name>() is not
supported`); index assignment, `push`, `pop`
and `removeAt` through an `any` value (`push(a[0], 1)` where `a` is
`any[]`: the reference uses `invokeinterface`, which raises
`IncompatibleClassChangeError` for a non-list; SiyoLLVM does not reproduce
that error); `sort`, `map`, `filter`, `reduce` and `forEach` (they take
lambdas); `for … in` over an `any`, a set or a channel; sum types,
interfaces (so `impl Named for Point` is a bind error, `Type 'Named' does not exist`), actors (even when declared but unused);
fields of a type outside the subset (`float`, sets, …); `match` and `if` expressions; lambdas, closures and nested
functions that capture locals; generic functions; a `try` expression
anywhere else, or one whose body ends in a call with no value
(`try { println(x) } catch e { … }`; the reference fails to compile it).
Where the reference evaluates a `try` expression on a non-empty operand
stack, it fails with `VerifyError`: the right operand of an arithmetic or
comparison operator, `x += try …`, an argument of `print`/`println`, a
string builtin's argument after the first, an argument after a function's
first. Some other positions work on the reference: a left operand, the
right operand of `&&`/`||`, a string builtin's first argument and
`error()`'s argument. SiyoLLVM rejects those too, for now. Also rejected:
a catch variable of a type outside the subset; casts; `import java` (in the main file before binding; in a module, its Java calls are rejected); `std/…` modules (SiyoLLVM ships no standard library yet: `Module 'std/io' not found`); a module that imports its importer (the binder's circular import diagnostic); two different files that `siyoc` would give the same JVM class name, which it derives from the import string (`import "util"` reaching both `util.siyo` and `lib/util.siyo`: `[llvm] two modules named Util (util.siyo, lib/util.siyo) are not supported by SiyoLLVM`), and one file imported under two class names (`"lib/util"` and, from `lib/`, `"util"`); an import the binder gave up on without a diagnostic (a directory named `x.siyo`: `[llvm] module 'x' could not be compiled (x.siyo)`); in a module, the type declarations rejected in the main file (sum types, interfaces, actors), reported against the module's file; scope/spawn/send; `len` of anything but
a string or an array; every builtin not listed above;
`main`/`init` with parameters or a return value. A `try` expression is
rejected as an array element, an index, an assigned element or a pushed
value (the reference's operand stack is not empty there).

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
- Arrays are never freed either. An array's items are one block that
  doubles when full (at least 4), so `push` is amortised constant time; an
  array literal starts with room for exactly its elements. Boxing an int in
  -128..127 returns a shared static box, as `Integer.valueOf` does, so
  storing small ints in arrays allocates nothing per element; other ints,
  longs and bools are boxed into a new 16-byte object each time.
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
- An array is a reference, as on the JVM: two variables, a parameter, a
  returned array and an element of another array can all name one array,
  and a change through any of them is seen through the others. Its items
  are `any` values (an int, long or bool boxed), like the `ArrayList<Object>`
  inside the JVM's `SiyoArray`; a read is narrowed to the type the binder
  gives it (an `int[]` element is an `int`; an element of an `any[]`, or of
  a row of a nested literal, is an `any`) with the checked cast of an `any`
  read, so a wrong type raises `ClassCastException` (an array is
  `class codeanalysis.SiyoArray … (codeanalysis.SiyoArray is in unnamed module of loader 'app'; …)`,
  and a non-array read as an array is `… cannot be cast to class java.util.List …`).
- `xs[i] = v` is `List.set`: its value is the element it replaced, narrowed
  to the type of `v` even when the value is not used, as the JVM backend
  does, so `a[0] = 5` on an `any[]` whose element 0 is a string stores 5
  and then raises `ClassCastException`.
- An index outside `0..len-1` (in `xs[i]`, `xs[i] = v`, `removeAt`), and
  `pop` of an empty array, raise
  `java.lang.IndexOutOfBoundsException: Index 5 out of bounds for length 3`
  (`Index -1 out of bounds for length 0` for `pop`); a catch variable gets
  `Index 5 out of bounds for length 3`. A null array (read from an `any`
  holding null, or a global before its initialiser) raises
  `java.lang.NullPointerException` (see Deviations).
- Arrays print as `SiyoArray.toString` writes them: `[1, x, null, [2, 3]]`,
  each element by `String.valueOf`, nested arrays recursively. An array that
  contains itself recurses until the stack runs out, as on the JVM, and the
  program stops with `java.lang.StackOverflowError` (not catchable; see
  Deviations). `==` between `any` values holding arrays compares the elements
  in order (`[1]` holding an int is not equal to `[1L]`).
- A struct is a reference, as on the JVM, where it is a `SiyoObject` (a
  `LinkedHashMap` of field names to values carrying the struct's name): two
  variables, a parameter, an array element and a field of another struct can
  all name one struct, and `p.x = v` through any of them is seen through the
  others. Its fields are kept in the order the literal wrote them, which is
  the order it prints in: `Point { y: 2, x: 1 }`, each value by
  `String.valueOf`, nested structs and arrays included. A field the literal left out reads as null
  and, once written, is added after the others. Field values are `any`
  values (an int, long or bool boxed); a read is narrowed to the field's
  declared type with the checked cast of an `any` read, and the binder gives
  a read the declared type only when it can tell which struct the target is
  (otherwise, as for a variable declared `p: Point` from an `any`, the read
  is an `any`). A null field read into an `int` or `bool` raises
  `java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because the return value of "java.util.Map.get(Object)" is null`
  (`Boolean.booleanValue()` for bool), also in `p.x += 1`, which reads the
  field (evaluating `p` twice) and then writes it. An `any` read as a struct
  is a checkcast to `java.util.Map`, except an argument of a struct
  parameter, which the reference casts to `java.util.LinkedHashMap`; a wrong
  type raises `ClassCastException` naming that class, and a struct read as
  something else names `codeanalysis.SiyoObject`. `==` between `any` values
  holding structs is `AbstractMap.equals`: the same field names with equal
  values, in any order, whatever the struct names (`Point { x: 1 }` equals
  `Other { x: 1 }`). `typeName(x)` is a struct's name, and `""` for any
  other value. A struct that contains itself prints until the stack runs
  out (`java.lang.StackOverflowError`, as for arrays).
- A map is a reference too, as on the JVM, where it is a `SiyoMap` over a
  `LinkedHashMap`: keys and values are `any` values (an int, long or bool
  boxed), kept in insertion order, which is the order `m.keys()`, `for k in
  m` and printing (`{b: 3, a: 1}`, each key and value by `String.valueOf`)
  follow. Setting a key that is present keeps its place; removing a key and
  setting it again appends it. Keys compare as `Objects.equals` does: `1`
  and `1L` are different keys, equal strings are one key however they were
  built, `null` is a key like any other, arrays compare by elements and
  structs by fields; a map key is only equal to itself. A missing key reads
  as null (`m.has(k)` tells a missing key from a null value); read into an
  `int` or `bool` it raises `java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because the return value of "codeanalysis.SiyoMap.get(Object)" is null`
  (`get(String)` for `m.get(k)` with a string key, the overload the binder
  picks; into a `long`, `… "java.lang.Long.longValue()" because "value" is
  null`), also in `m[k] += 1`. A value of the wrong type raises
  `ClassCastException`. `for k in m` iterates over a fresh array of the keys
  taken when the loop starts, so keys set or removed in the body do not
  change which keys it visits. `len(m)` of a null map is 0; every other use
  of a null map raises `NullPointerException` (see Deviations). The table is
  never freed; it doubles when full, and removed entries are dropped then.
- `for x in xs` is the binder's index loop: it walks the array the
  expression named when the loop began (assigning the variable another
  array does not change that), and reads `len` before every iteration, so
  elements pushed during the loop are visited, a `pop` ends it early and
  removing the current element skips the next one, as on the JVM. The loop
  variable is read with the checked cast of an element read. Over a string
  it walks UTF-16 units, each a one-unit string (half of a pair is a lone
  surrogate).
  The loop variable is typed `any` there, as in the reference, so `len(c)` and
  `ord(c)` on it are rejected; copy it first with a typed declaration
  (`imut s: string = c`), after which the string builtins work.
- `range(a, b)` is a new array of the ints `a` to `b - 1`, each boxed (only
  -128..127 come from the static cache), allocated up front: `range(0, 100000)`
  takes about 2.4 MB before allocator overhead. It is empty when `a >= b`, including reversed bounds.
- `split(s, d)` is the reference's `s.split(Pattern.quote(d), -1)`: a
  literal split (`.`, `|`, `*`, `[x]` are plain text) that keeps trailing
  empty strings. A string without the delimiter gives `[s]`, so `split("", ",")`
  is `[""]` (length 1; it prints as `[]` like an empty array). An empty
  delimiter splits between every two UTF-16 units and after the last, not
  before the first: `split("abc", "")` is `[a, b, c, ""]` and splits surrogate
  pairs. A delimiter containing a surrogate matches by code point, as
  `java.util.regex` does, so a lone half never matches half of a pair. Every
  part is a new string.
- `s[i]` is `charAt`: one UTF-16 unit as a string, so half of a surrogate
  pair is a lone surrogate. Out of range it raises
  `java.lang.StringIndexOutOfBoundsException: Index 5 out of bounds for length 5`
  (a catch variable gets the message). Like `substring`, it scans a
  non-ASCII string from the start.
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
| A null array (from an `any`, or a global read before its initialiser) indexed, assigned into or passed to `len`/`push`/`pop`/`removeAt` | `java.lang.NullPointerException: Cannot invoke "java.util.List.get(int)" because "<local0>" is null` (a helpful message naming JVM internals) | `java.lang.NullPointerException`; a catch variable receives `NullPointerException` |
| A null map used as a receiver (`m[k]`, `m[k] = v`, `m.get(k)` and the other methods, `for k in m`) | `java.lang.NullPointerException: Cannot invoke "codeanalysis.SiyoMap.get(Object)" because "<local0>" is null` (the method and overload called; a helpful message naming JVM internals) | `java.lang.NullPointerException`; a catch variable receives `NullPointerException`. When the null map came from a map read (e.g. `mm["q"]["zz"]`) the JVM message is deterministic (`Cannot invoke "codeanalysis.SiyoMap.get(Object)" because the return value of "codeanalysis.SiyoMap.get(Object)" is null`) while SiyoLLVM prints a bare `java.lang.NullPointerException` |
| A null struct (from a variable or field holding null) whose field is read or written | `java.lang.NullPointerException: Cannot invoke "java.util.Map.get(Object)" because "<local3>" is null` (`Map.put(Object, Object)` for a write; a helpful message naming JVM internals) | `java.lang.NullPointerException`; a catch variable receives `NullPointerException` |
| Null `any` or array element (other than the literal `null`) read into an `int` or `bool` | `java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because the return value of "<Class>.f(Object)" is null` (the helpful message names where the null came from: a call, `"<local1>"`, a field, `"java.util.List.get(int)"` for an element, `"java.util.List.set(int, Object)"` for the element an assignment replaced) | `java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()"` (`Boolean.booleanValue()` for bool), without the `because …` part; a catch variable receives that shorter message |
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
