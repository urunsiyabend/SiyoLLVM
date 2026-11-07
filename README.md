# SiyoLLVM

An **experimental** compiler from a subset of [Siyo](https://github.com/urunsiyabend/SiyoCompiler)
to real textual LLVM IR, and with clang, to native executables.

```
$ bin/siyollvm build examples/fibonacci.siyo -o fibonacci
$ ./fibonacci
0 1 1 2 3 5 8 13 21 34 55 89 144 233 377 610
2880067194370816120
```

SiyoLLVM is a separate project from SiyoCompiler. It reuses SiyoCompiler's
frontend (lexer, parser, binder, lowerer) from a pinned, unmodified snapshot
and adds its own subset checker, LLVM IR emitter, toolchain driver and
command-line tool. The JVM compiler (`siyoc`) is not involved at run time.

What it accepts, how programs behave, and where the behaviour differs from
`siyoc run` is in **[docs/SUBSET.md](docs/SUBSET.md)**. In short: `int`,
`long`, `bool` and `string` values, `any` values holding them (boxed, with
checked casts back), `null`, all statement-level control flow,
functions and recursion, `print`/`println`, string concatenation and
interpolation, and the string builtins (`len`, `substring`, `indexOf`, … with
Java's UTF-16 indexing). Arrays (`T[]`, literals, indexing with
bounds errors, `push`/`pop`/`removeAt`/`len`), `for … in` over arrays, strings
and `range(a, b)`, `split`, and string indexing `s[i]`. Structs (literals, fields, `impl` methods, `Type.new`) and enums. Insertion-ordered maps (`Map<K, V>`, `{…}` literals, `map()`; index read and write, `has`/`get`/`set`/`remove`/`keys`/`size`, `len`, `for k in m`). Exceptions: `throw`, `error()`, `try`/`catch`
statements, and `try` expressions in the positions SUBSET.md lists; runtime
errors (`/ by zero`, a failed cast, `parseLong` of bad input, an index out
of range, …) are catchable, and an uncaught one prints the JVM's exception
line and exits with status 1. Multi-file programs: `import "x"`, `import "dir/x"`
and `import "x" as y` of user modules, plus the native std modules `std/io`
(file I/O), `std/os` (arguments, exit) and `std/strings`. Everything else is
rejected with a diagnostic, including code that is never reached (and
`import java`).

Building a multi-file program and running it with arguments (imports resolve
relative to the importing file, as in `siyoc`):

```
$ bin/siyollvm build path/to/main.siyo -o app
$ ./app input.csv out.txt     # read in the program with os.args()
```

## Requirements

- Java 21 and Maven 3.9 to build.
- clang 15 or newer to produce executables (CI uses clang 19). `emit-llvm`
  works without clang.
- Linux (glibc), x86_64 or aarch64.

## Build

```
mvn verify            # unit + integration tests; does not need clang
mvn verify -Pnative   # also builds and runs every example and corpus program with clang
```

The native gate fails, rather than skips, when clang cannot be found. Point
it at a specific clang with `SIYOLLVM_CLANG`:

```
SIYOLLVM_CLANG=clang-19 mvn verify -Pnative
```

## Usage

```
bin/siyollvm emit-llvm <file.siyo> [-o out.ll]
bin/siyollvm build <file.siyo> [-o exe] [--clang <path>] [--emit-llvm out.ll]
bin/siyollvm emit-runtime <directory>
bin/siyollvm --help | --version
```

`emit-runtime` writes `siyo_rt.c` and `siyo_rt.h` into the directory (created
if missing; existing copies are overwritten). To link by hand:

```
bin/siyollvm emit-llvm prog.siyo -o prog.ll
bin/siyollvm emit-runtime .
clang -O1 -x ir prog.ll -x c -std=c11 siyo_rt.c -pthread -o prog
```

`bin/siyollvm` runs `target/siyollvm-<version>.jar` (built by `mvn package`);
`java -jar target/siyollvm-<version>.jar …` works the same way. clang is
looked up as `--clang <path>`, then `$SIYOLLVM_CLANG` (a path or a command
name), then `clang` on `PATH`. SiyoLLVM calls clang as
`clang -O1 -fverify-intermediate-code -w -x ir <file.ll> -x c -std=c11 <dir>/siyo_rt.c -pthread -o <exe>`,
where `<dir>` is a temporary copy of the C runtime shipped in the jar, created
next to the executable and removed afterwards (a `.siyo-rt*` directory can be
left behind if the compiler process is killed mid-build).

Exit status: 0 on success, 1 for diagnostics, unreadable input or a clang
problem, 2 for usage errors, refused outputs and internal errors. Diagnostics use siyoc's
format, `file(line, col): message`; constructs outside the subset read
`[llvm] … not supported by SiyoLLVM`.

Outputs are checked before anything is written:

- An option value may not start with `-` (`-o --missing` is a usage error);
  name such a file `./-file`.
- No output (`-o`, the default executable, `--emit-llvm`) may be the source
  file, whether by the same path, a symlink or a hardlink; and `build`'s
  executable and `--emit-llvm` file must differ. These are refused with exit 2
  and nothing is written.
- Files are written beside their target under a temporary name and renamed
  into place when complete. A failed or timed-out clang leaves no partial
  executable and keeps any previous one.

clang gets 30 s for `--version` and 600 s per compile; past that, clang and
every process it started are killed and the build fails with exit 1.

Sources must be UTF-8. Under a non-UTF-8 locale (such as `LC_ALL=C`) the
JVM cannot decode non-ASCII file names given on the command line, so
`bin/siyollvm` runs the compiler under `C.UTF-8` in that case; with
`java -jar` you get a clear error instead. Compiled programs always write
UTF-8 bytes.

## Layout

| Path | What |
|---|---|
| `src/main/java/siyollvm/frontend` | runs the snapshot frontend; binds imports (rejects only `import java`), checks module class-name collisions and dropped imports, and builds multi-unit programs |
| `src/main/java/siyollvm/check` | `SubsetValidator`: whitelist over every lowered statement and expression |
| `src/main/java/siyollvm/ir` | `IrEmitter` and the runtime declarations |
| `src/main/resources/siyollvm/runtime` | the C11 runtime (`siyo_rt.c`, `siyo_rt.h`): `main`, program thread, stack guard, printing, strings and string builtins, file I/O |
| `src/main/resources/std` | SiyoLLVM's own std sources (`io`, `os`, `strings`); io and os are native intrinsics |
| `src/main/java/siyollvm/toolchain` | finding and running clang; writing out the runtime source |
| `src/main/java/siyollvm/cli` | the `siyollvm` command |
| `upstream/siyocompiler` | the frontend snapshot, its provenance and checksums |
| `examples/` | example programs with expected output |
| `src/test/resources/modules` | directory corpus of multi-file programs, incl. `main.args`, `main.locale` and file fixtures |
| `src/test/resources/native` | semantics corpus (expected output from `siyoc run` 0.9.0) |

## Frontend snapshot

`upstream/siyocompiler/src/main/java` holds 157 files copied unmodified from
SiyoCompiler at `02c226298296fb4379f7251a9dfba19b7e887f3d` (0.9.0), by
Siyabend Ürün. Why each file is there and how to update the snapshot is in
[upstream/siyocompiler/PROVENANCE.md](upstream/siyocompiler/PROVENANCE.md);
`scripts/update-upstream.sh` performs the update, and a test fails if the
snapshot drifts from its checksum manifest. SiyoLLVM adds no licence terms
for these files.

Two pieces of SiyoLLVM's own code follow the reference and must be checked
when the snapshot is updated: `Frontend.Imports.moduleClassName` copies
siyoc's package-private `ModuleHandler.moduleClassName` (how it names a
module's JVM class), and `src/main/resources/std/strings.siyo` reimplements
the reference std's `strings` functions with the same algorithms (see
[docs/SUBSET.md](docs/SUBSET.md#standard-library)).

## Status

Experimental. No releases. The design is in
`docs/superpowers/specs/2026-11-06-siyollvm-design.md`.
