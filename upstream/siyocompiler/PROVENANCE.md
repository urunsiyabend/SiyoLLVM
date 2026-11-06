# SiyoCompiler frontend snapshot — provenance

The Java sources under `src/main/java/` in this directory are copied,
unmodified, from **SiyoCompiler** by Siyabend Ürün:

| | |
|---|---|
| Repository | `urunsiyabend/SiyoCompiler` |
| Commit | `02c226298296fb4379f7251a9dfba19b7e887f3d` (release 0.9.0) — also in `COMMIT` |
| Files | listed in `FILES`; SHA-256 of each in `MANIFEST.sha256` |
| Author | Siyabend Ürün |

SiyoCompiler carries no licence file at that commit. SiyoLLVM does not add,
infer, or change licensing terms for these files; they are included here by
their author, who owns both repositories. Package names (`codeanalysis.*`)
and file contents are kept exactly as upstream, so differences can be
reviewed with a plain diff against the pinned commit.

## Why these files

SiyoLLVM reuses the SiyoCompiler frontend — lexer, parser, binder, lowerer —
and nothing after it. `FILES` is the transitive compile closure of a probe
that parses, binds and lowers a program (`javac -implicit:class` with the
upstream tree on the source path), 157 of the 168 Java files:

- `codeanalysis/syntax/*`, `codeanalysis/text/*` — lexer, parser, syntax tree.
- `codeanalysis/binding/*`, `codeanalysis/lowering/Lowerer.java` — binder and
  lowering to labels/gotos, which the LLVM emitter consumes.
- Symbols and diagnostics: `FunctionSymbol`, `VariableSymbol`,
  `ParameterSymbol`, `LabelSymbol`, `StructSymbol`, `UnionSymbol`,
  `InterfaceSymbol`, `ModuleSymbol`, `Diagnostic`, `DiagnosticBox`,
  `BuiltinFunctions`, `FunctionTypeSignature`.
- Type tokens the binder refers to by class (`SiyoArray`, `SiyoMap`,
  `SiyoSet`, `SiyoStruct`, `SiyoUnion`, `SiyoClosure`, `SiyoChannel`). SiyoLLVM
  rejects every program that uses them; they are needed only to compile the
  binder.
- Java-interop metadata (`Java*.java`; needs ASM 9.6 at compile time) and
  `ModuleRegistry` + `project/*`: referenced by the binder's import handling.
  SiyoLLVM rejects `import` before binding, so none of it runs.

Excluded: the JVM bytecode emitter, the interpreter (`Evaluator`), runtime
support (`SiyoRuntime`, `SiyoActor`, `SiyoHttp`, …), `Compilation`, `Main`,
the standard library `.siyo` files, and all upstream tests.

## Updating

1. In a SiyoCompiler checkout, pick the new commit.
2. Run `scripts/update-upstream.sh <checkout> <commit>`. It re-extracts each
   path in `FILES` with `git show <commit>:<path>` (the checkout's working
   tree is never read or changed) and rewrites `MANIFEST.sha256` and
   `COMMIT`.
3. If the build fails because the frontend now needs another file, add that
   path to `FILES` and re-run step 2. If a file is no longer needed, remove
   it from `FILES`. Recompute the closure with the probe described above if
   in doubt.
4. Update the commit in this file, then run `mvn verify` and
   `mvn verify -Pnative`. Binder changes can introduce new bound node kinds;
   `SubsetValidator` rejects anything it does not know, so new constructs
   show up as `[llvm] … is not supported` until the backend handles them.

`SnapshotManifestTest` fails if any snapshot file is edited, added or removed
without going through this procedure.
