# Frontend survey for native backend features (2026-11-07)

Read-only survey of the pinned SiyoCompiler frontend and JVM runtime, produced while designing
the native toolchain milestone. Paths: snapshot = `upstream/siyocompiler/src/main/java/codeanalysis/`;
pinned = SiyoCompiler `02c2262` read with `git show`.

## 1. Imports

**How `import "x"` finds a file** (`binding/ModuleHandler.java:426-501`, `resolveModulePath`). Dots become slashes (`"util.str"` → `util/str`). Candidates, in order:
1. `<dir of _filePath>/x.siyo`
2. `<dir>/x/index.siyo`, then `<dir>/x/x.siyo`
3. Only if `SiyoProject.getCurrent() != null`: the same two checks under the project's `src/` root (`:457-477`). That static is set only by SiyoCompiler's `Main` (`loadOwningProject`). Nothing in the binder loads `siyo.toml`, so no project root is needed and none is used unless you set it.
4. `$CWD/x.siyo`
5. Only for `std/...`:
   - first `$CWD/src/main/resources/std/x.siyo` ("dev mode", `:489`);
   - then a classpath resource `std/x.siyo`, using `ModuleHandler.class.getClassLoader()`. It returns the pseudo-path `"classpath:std/x.siyo"` (`:494-497`), which `compileModule` reads with `getResourceAsStream` (`:508-513`).
   - So std modules come from whatever jar holds `codeanalysis.*`, which for SiyoLLVM is your jar. SiyoLLVM ships no `std/`.

**Bug: imports crash with your current filename.** `:427-428` calls `Paths.get(_filePath).getParent().toString()`. `cli/Main.java:146` passes `sourcePath.getFileName().toString()` (a bare name) through `Compiler.toIr` to `Frontend.analyze`, and that becomes `_filePath`. `getParent()` is then null and the first import throws an NPE. You must pass the absolute path.

**How a module is compiled** (`compileModule`, `:503-609`):
- It runs a fresh `Binder` on the module. `bindStatement` keeps `_atFileTopLevel=true`, so top-level rules apply to modules too.
- Any exception is swallowed and returns null with no diagnostic of its own (`:605-608`).
- The result is a `ModuleSymbol` (`ModuleSymbol.java`), registered in `ModuleRegistry` keyed by absolute path or the `classpath:` path. It holds:
  - `getFunctionBodies()`: only the module's own functions (`getModuleName()==null`), **not lowered** for plain fns; impl-method bodies are already lowered (`Binder.java:3398`).
  - `getTopLevelBlock()`: the lowered module top level.
  - `getVariables()`: name → the module's own `VariableSymbol` (`:635-647`, skipping `_idx/_col/_ch`).
  - structs, enums, unions, interfaces, `getExportedNames()` (null means everything exported), `getImportedClassNames()`, `getInheritedMethods()`.
  - `initFunction()`: its own zero-arg `init`.
- `getClassName()` comes from `moduleClassName` (`:350-360`): `"geometry"` → `Geometry`, `"util/str"` → `Util_Str`, `"std/io"` → `Siyo_Io`.

**What the importer gets** (`bindImportStatement`, `:149-336`). Everything goes into the importer's `BoundScope` and `_functionBodies`, so it ends up in one combined `BoundGlobalScope`:
- For each exported module function, a **new** `FunctionSymbol("q.fn", params, ret, moduleName=className)` with `jvmMethodName = fn.replace('.', '$')` and the module's `originModule` (`:221-237`). Here `q` is the alias or the last path segment.
  - Its body is the **same object** as the module's body. Only the qualified symbol carries the body in the importer.
  - Impl methods are also registered bare as `"Vec2.new"` with owner = className (`:312-333`).
- Module variables become **new** `VariableSymbol("q.name")` with `setOwner(className, name)` (`:260-271`).
- Structs, enums, interfaces and unions are copied into the importer's maps **by bare name**. Two modules exporting the same struct name collide.
- `Binder.bindGlobalScope` (`Binder.java:83-98`) returns the importer's body map (own plus imported-qualified symbols), its struct types, and `getImportedClassNames()`.
- **There is no API that returns a `BoundProgram` per module.** The registry is the only handle to module globals, `init`, and the module-internal symbols. Frontend currently creates `new ModuleRegistry()` inline (`Frontend.java:50`) and throws it away; you need to keep it.
- Inside a module body, a call to its own function uses the module-local symbol (owner null), not the importer's `q.fn`. A call into another imported module uses that module's qualified symbol. A read of its own global is the module's original `VariableSymbol`.
- A workable global identity is (owner className, or "main" when null; `jvmMethodName` or `name.replace('.', '$')`; arity). That matches `FunctionSymbol.equals`/`hashCode` (name, moduleName, originModule, arity).

**What the call sites bind to:**
- `mod.fn(x)` → `BoundCallExpression(importedSymbol, args)` (`Binder.java:2931-2970`). Inside module `db`, `db.exec()` resolves to the bare `exec` (`:2939-2944`).
- `mod.var` → `BoundVariableExpression(VariableSymbol "mod.var", ownerClass=…, fieldName=…)` (`Binder.java:2323-2327`).
- `import` itself binds to `BoundExpressionStatement(BoundLiteralExpression(0))`. It is processed in the block's first pass (`:200-203`), and the second pass is deduplicated by `importKey`.

**Init order:**
- The registry is a `LinkedHashMap` in completion order, so dependencies come first (`ModuleRegistry.java:9-11`).
- The JVM main does: store args, then `Class.forName` each imported class (whose `<clinit>` force-loads its own imports, runs the top-level block, then `init()`; `Emitter.java:258`), then the main file's top level, then `init()`, then `main()` (`Emitter.java:290-323`).
- The interpreter does the same: every registry module's top level then its `init`, in registry order (pinned `Compilation.java`, `evaluate` / `initialiseModule`).
- Main-file `init`/`main` are found with `getModuleName()==null` and zero params.

## 2. What `Frontend.java` does now

- Parse → reject syntax diagnostics → `findUnsupportedDeclarations` (`:96-117`).
- Before binding it rejects `ImportStatement`, `JavaImportStatement`, `StructDeclaration`, `EnumDeclaration`, `TypeDeclaration`, `InterfaceDeclaration`, `ImplDeclaration` and `ActorDeclaration`.
- Then `Binder.bindGlobalScope(null, root, new ModuleRegistry(), file, true)` (`:50`).
- Then it lowers each body in `scope.getFunctionBodies()`, ordered by declaration offset; imported symbols would get `Integer.MAX_VALUE`. It lowers `getBoundStatement()` as globals.
- `SubsetValidator` then rejects the remaining node kinds (arrays, index, maps, member access, structs, try, throw, Java interop and others; `check/SubsetValidator.java:65-86`).
- The `siyollvm` CLI accepts only one source file, so it has no way to pass program arguments.

## 3. Command-line args and std

**Getting args:**
- `siyoc run file.siyo a b` puts `cargs[2..]` in `_programArgs` (pinned `Main.java:68-75`). The script name is excluded. Project mode uses `cargs[1..]`.
- The JVM main stores `String[]` into `SiyoRuntime.programArgs` (`Emitter.java:302`). The interpreter path sets the same field (`Main.java:408`).
- Programs read them with `import "std/os"` then `os.args()`, which returns `string[]` via `SiyoRuntime.getProgramArgs()` (`SiyoRuntime.java:17`).
- There is no builtin for args and no `main(args)`; only a zero-arg `main` is called.

**std files at the pinned commit** (`src/main/resources/std/`):

| File | Uses Java interop? |
|---|---|
| `html` | No (pure Siyo) |
| `io` | Yes: `FileWriter`, `BufferedReader`/`FileReader`, `File`, `System.err`, `StringBuilder`, `SiyoRuntime.walkFiles/readBytes/...` |
| `json` | Yes: `SiyoRuntime` |
| `math` | Yes: `StrictMath` |
| `net` | Yes |
| `os` | Yes: `args`, `env`, `exit`, `cwd` |
| `path` | Yes: `File`, `System` |
| `strings` | Yes: `StringBuilder`, `SiyoRuntime` |
| `testing` | Yes: `SiyoRuntime` |

- Notable `std/io` behaviours: `readFile` joins lines with `\n` and drops the final newline; `readLines` builds `[""]` then `removeAt(0)`.
- **Hard:** every std module except `html` uses `import java`. That binds through ASM class metadata (`JavaClassMetadata.load`, `:43-65`) and produces `BoundJavaMethodCallExpression`. `codeanalysis.SiyoRuntime` is not in the snapshot, so the binder would report "module not found".
- Practical route: ship your own `std/*.siyo` resources in the SiyoLLVM jar (`classpath:` lookup) without `import java`, and have the backend replace specific bodies by `(className "Siyo_Io"/"Siyo_Os", fn)` with native code.
- Watch the dev-mode override: if `$CWD/src/main/resources/std/` exists, it is used first.

## 4. Bound nodes and types

**How types are represented:**
- Types are `Class<?>` tokens, no `TypeSymbol`. `TypeResolver.lookupType` (`binding/TypeResolver.java:520-570`) maps:
  - `int` → `Integer`, `long` → `Long`, `float` → `Double`, `bool` → `Boolean`, `string` → `String`
  - `T[]`, `Array<T>`, `List<T>` → `SiyoArray`; `Map<..>` and `map` → `SiyoMap`
  - a struct or interface → `SiyoStruct`; a union → `SiyoUnion`; `fn` → `SiyoClosure`
  - `object`/`any` and type parameters → `Object`
- Element and value types are **erased**. The binder's per-variable tracking (`TypeResolver._typeInfo`) is private and not in `BoundGlobalScope`. A backend can see only:
  - `BoundArrayLiteralExpression.getElementType()`
  - `BoundIndexExpression.getClassType()`: the element or value type, or `Object`
  - `VariableSymbol.getDeclaredTypeName()`: the raw string such as `"Point[]"` or `"Map<string,int>"`, set only when annotated or inherited (`Binder.java:391-405`, params `:1849`)
  - the `FunctionSymbol` return element and struct names
- **Hard:** `BoundMemberAccessExpression` carries only target, name and field type. No `StructSymbol` is reachable from the node. Plan for dynamic field lookup by name, or a runtime struct id, which is what the JVM does with a `LinkedHashMap`.
- The JVM backend uses `SiyoObject extends LinkedHashMap<String,Object>` for structs.

**Struct-related nodes:**
- A struct or impl declaration binds to the placeholder `BoundExpressionStatement(literal 0)`. Field info lives in `StructSymbol`, a `LinkedHashMap` of field → `Class` plus field type names (`StructSymbol.java`); you get them via `scope.getStructTypes()`.
- Struct literal: `BoundStructLiteralExpression(StructSymbol, LinkedHashMap<field, expr>)` in source order, which may differ from declaration order (`Binder.java:2434-2461`).
- Member read: `BoundMemberAccessExpression(target, name, type)` (`:2300-2386`).
- `p.x = v` and `p.x += v` parse as `CompoundAssignmentExpressionSyntax`. `+=` is desugared to `p.x = p.x + v`, which evaluates the target twice (`syntax/Parser.java:1238-1256`). It binds to `BoundMemberAssignmentExpression(target, name, value)` (`Binder.java:2397-2408`).

**Impl methods:**
- Registered as `FunctionSymbol("Point.isPost", [self: SiyoStruct, ...])` (`ModuleHandler.java:726-775`).
- `p.isPost()` → `BoundCallExpression(Point.isPost, [p, ...args])`, so **self is the first argument** (`Binder.java:3032-3044`). `Point.new(1, 2)` → a plain call with no self.
- A field holding a function → `BoundClosureCallExpression`.
- On values whose type is string, array, map, set, union or number, `x.f(a)` first tries a function `f(x, a)` whose first parameter accepts the receiver (`:3070-3073`, `:3124-3146`). So `s.split(",")`, `arr.push(x)` and `arr.len()` become builtin `BoundCallExpression`s.
- **Hard:** anything that falls through binds to `BoundJavaMethodCallExpression`, resolved by ASM over the real Java class:
  - `m.get(k)` → `Object`, `m.set`, `m.has` → bool, `m.keys()` → `SiyoArray`, `m.remove`, `m.size`, `m.getInt`, … (the `SiyoMap` methods)
  - `s.length()` and other `java.lang.String` methods
  - arrays with an unknown method (null class info)
  
  Match these by `getMethodName()` and `getTarget().getClassType()`.

**Arrays and maps:**
- Array literal: `BoundArrayLiteralExpression(elements, elementType)`. Mixed element types are an error; empty is `Object` unless annotated (`:2248-2267`, `:617-628`).
- Index: `BoundIndexExpression(target, index, resultType)`. Map keys can be anything; others need an int; indexing a string gives a one-character string (`:2269-2298`).
- `a[i] = v` → `BoundIndexAssignmentExpression` (`:2391-2395`).
- Map literal: `BoundMapLiteralExpression(keys, values)`. `map()` → `NEW_MAP`.
- Builtins (`BuiltinFunctions.java`): `len(Object)`, `push(SiyoArray, Object)`, `pop`, `removeAt`, `sort(arr, fn)`, `split`, `substring(s, a, b)`, `contains`, `indexOf`, `replace`, `trim`, `toUpper`/`toLower`, `parseInt`, `toString`, `error`, `range`, and others. Compare them by identity against the `BuiltinFunctions` constants (`isBuiltin` uses `==`).

**For-in** (binder, `Binder.java:2069-2201`):
- A map is wrapped in `$mapKeys(m)`, a set in `$setValues(s)`, an `Object` in `$iterable(x)`; channels are special.
- It becomes a block containing `_colN = coll` and a `BoundForStatement` with `_idxN = 0`, condition `_idx < len(_col)`, step `_idx = _idx + 1`, and body `item = _col[_idx]` (a `BoundIndexExpression`) followed by the body.
- The Lowerer turns `BoundForStatement` into labels and gotos (`lowering/Lowerer.java:83-109`).
- `len` is re-evaluated every iteration, so pushes during the loop are seen.

**String `+` and interpolation:**
- `+` is `BoundBinaryExpression` with `BoundBinaryOperatorType.Addition` and result type `String`. It is **not** a call.
- Defined pairs are `String` with `String`/`Integer`/`Long`/`Boolean`/`Double`/`Object`, both directions (`BoundBinaryOperator.java:54-55`, `:92-100`). `"x" + arr` with a non-`Object` array, map or struct is a **bind error**.
- `$x` / `${e}` interpolation is handled in the lexer and parser (`Lexer.java:409-430`, `Parser.java:1618-1660`). It becomes a chain of `+`, with a marker token (`Parser.INTERPOLATION`).
- For interpolation only, the binder wraps operands that `+` cannot take in `toString(...)` (`Binder.java:1279-1286`).
- `desugarStringInterpolation` (`:696`) is dead legacy code.

**try / throw / error:**
- `throw e` → `BoundThrowStatement(expr)`, any value (`:3527`).
- `try {..} catch e[: T] {..}` → `BoundTryCatchStatement(tryBody, errorVar, catchBody)`. The catch variable is `Object` unless annotated (`:3509-3571`).
- `try` used as an expression → `BoundTryExpression(tryBody, errVar, catchBody, type)`. Both bodies are already lowered by the binder (`:753-771`), and the type is the last expression's type, otherwise `Object`.
- `error(msg)` is a void builtin `BoundCallExpression`. The JVM backend makes it throw (`Emitter.java:2278-2284`), but the binder does **not** treat it as diverging (listed as open in RELEASE_NOTES_0.9.0).
- **Hard for codegen:**
  - The Lowerer keeps a `TryCatchStatement` as a structured node. Its bodies are rewritten but **not flattened**: nested blocks, labels and gotos stay inside (`BoundTreeRewriter.java:198-207`; `flatten` only splices top-level blocks).
  - Gotos from `break`/`continue` inside a try can jump to loop labels **outside** it, and returns can appear inside, so you must unwind or pop handlers.
  - Implicit return rewrites the tails of try/catch (`Binder.java:3496-3505`).
  - Labels are `LabelSymbol` objects with no `equals`, and each `Lowerer` restarts at "Label0", so key labels by identity. `IrEmitter` already uses an `IdentityHashMap`.

## 5. JVM runtime behaviour at the pinned commit

**toString and `+`:**
- `toString(x)` is `String.valueOf(boxed)` (`Emitter.java:2311-2315`). `println` uses `PrintStream.println(Object)`.
- Concatenation uses `StringBuilder.append`, typed for int, long, double, bool and String, otherwise `Object` (`Emitter.java:1343-1370`).

| Value | Printed as |
|---|---|
| int / long | decimal |
| bool | `true` / `false` |
| float | Java `Double.toString`: `1.0`, `1.0E10`, `NaN`, `Infinity` |
| null | `null` |
| array | `[1, 2, a]` (elements unquoted, via `valueOf`; `SiyoArray.toString`) |
| map | `{k: v, k2: v2}` unquoted (`SiyoMap.toString`) |
| struct (compiled) | `Point { x: 1, y: 2 }` (`SiyoObject.toString`) |
| closure | `[Ljava.lang.Object;@hash` |

**Arrays and strings:**
- Index out of bounds: `List.get` throws `IndexOutOfBoundsException("Index 5 out of bounds for length 3")`; negative indexes give the same message with `-1` (`Emitter.java:2143-2175`).
- String indexing uses `charAt` and throws `StringIndexOutOfBoundsException`.
- `substring` is `String.substring(a, b)` (`:2547`). Out of range throws `"begin 0, end 10, length 3"`; it never clamps.
- `split` is **literal**, not regex: `Pattern.quote(delim)` with limit `-1`, so **trailing empty strings are kept**. `"".split(",")` gives `[""]`, and `"abc"` split on `""` gives `[a, b, c, ""]` (`:2612-2625`).
- **Hard:** `len`, indexes and `substring` count **UTF-16 code units** (`lengthOf`, `SiyoRuntime.java:246`). A UTF-8 native runtime differs for non-ASCII text unless you emulate this.

**Maps:**
- `SiyoMap` wraps a `LinkedHashMap`: iteration is **insertion order**, and setting an existing key keeps its position.
- Keys use Java `equals`, so `Integer(1)` and `Long(1)` are different keys.
- A missing key returns `null` from `m[k]` and `m.get(k)`.
- Reading a missing key on a `Map<string,int>` slot unboxes null and throws a **NullPointerException** (`emitUnboxIfNeeded`, `Emitter.java:2975`; documented in RELEASE_NOTES_0.9.0).
- Equality (`==`) uses `Objects.equals`: arrays compare by elements, and compiled structs compare by field map, ignoring the struct name.

**Number parsing:**
- `parseInt` and `toInt(string)` go through `safeParseInt`: invalid input returns **0**, with no trimming, so `" 5"` gives 0 (`SiyoRuntime.java:403`).
- `parseFloat` returns 0.0 on invalid input.
- `parseLong` uses raw `Long.parseLong` and **throws** `NumberFormatException` (`Emitter.java` PARSE_LONG).

**Uncaught errors:**
- Nothing in the generated `main` catches. Siyo `Main` catches `InvocationTargetException` and calls `e.getCause().printStackTrace(System.err)` then `System.exit(1)` (`Main.java:241`, `:296-301`).
- So stderr shows `codeanalysis.SiyoThrow: <payload.toString()>` followed by a stack trace, and the **exit code is 1**. `SiyoThrow`'s message is `String.valueOf(payload)`.
- RELEASE_NOTES_0.9.0 lists this as an unstable, per-backend format.

**What a catch variable receives:**
- The handler catches `java/lang/Throwable` (`Emitter.java:587`), so it catches everything, including `StackOverflowError`.
- The value is `SiyoRuntime.errorPayload(t)` (`SiyoRuntime.java:215-240`): the raw payload for a Siyo `throw` or `error()`, otherwise the Java exception's `getMessage()`, or its simple class name if there is no message. Examples:
  - integer divide by zero → `"/ by zero"`
  - array out of bounds → `"Index 5 out of bounds for length 3"`
  - missing file → `"x.txt (No such file or directory)"`
  - stack overflow → `"StackOverflowError"`
- **Hard:** matching these JVM message strings exactly in native code is effort you choose to spend; tests compare them (e.g. `CliParityTest`).

**sort:**
- `sort(arr, fn)` sorts in place via `SiyoRuntime.sortList` (`:46-90`), using Java `List.sort`: TimSort, **stable**.
- The comparator must return an int (<0, 0, >0). A non-int result counts as 0, and any exception thrown by the comparator is swallowed as 0.
- An inconsistent comparator may make TimSort throw `IllegalArgumentException`.