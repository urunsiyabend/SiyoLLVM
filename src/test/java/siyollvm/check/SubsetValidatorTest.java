package siyollvm.check;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import siyollvm.frontend.Frontend;
import siyollvm.frontend.FrontendResult;
import siyollvm.frontend.SourceDiagnostic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubsetValidatorTest {

    static List<SourceDiagnostic> validate(String source) {
        FrontendResult result = Frontend.analyze("v.siyo", source);
        assertTrue(result.diagnostics().isEmpty(), "frontend rejected test program: " + result.diagnostics());
        return SubsetValidator.validate(result.program());
    }

    /** Declarations SiyoLLVM cannot compile are rejected by the frontend's syntax scan, the rest here. */
    static void assertRejected(String source, String construct) {
        FrontendResult result = Frontend.analyze("v.siyo", source);
        List<SourceDiagnostic> diagnostics = result.program() == null
                ? result.diagnostics() : SubsetValidator.validate(result.program());
        assertTrue(diagnostics.stream().anyMatch(d ->
                        d.message().equals("[llvm] " + construct + " not supported by SiyoLLVM")),
                "expected '" + construct + "' in " + diagnostics);
    }

    @Test
    void supportedProgramHasNoDiagnostics() {
        String source = """
                mut counter = 0
                mut greeting = "hi\\0there"
                fn add(a: int, b: int) -> int { return a + b }
                fn wide(a: long, b: int) -> long { return a * b + b - 1L }
                fn check(flag: bool, s: string) -> bool { return !flag || s == "x" && s != greeting }
                fn main() {
                  mut i = 0
                  while i < 10 { i += 1 if i % 2 == 0 { continue } if i > 7 { break } }
                  for mut j = 0 j < 3 j = j + 1 { print(j) }
                  do { i = i - 1 } while i > 0
                  println(add(1, 2) << 3 >> 1 & 7 | 8 ^ ~1)
                  println(wide(5L, 2) / 2L % 3L)
                  println(-i + +i)
                  println(check(true, "x"))
                  println(1L == 1)
                  println(greeting)
                  counter = counter + 1
                }
                """;
        assertEquals(List.of(), validate(source));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            fn main() { mut d = 1.5 println(d) }                            | float values are
            fn main() { println("a" + 1.5) }                                | float values are
            fn main() { println("d=${1.5}") }                               | float values are
            fn main() { println(toString(1.5)) }                            | float values are
            fn main() { mut a = [1.5, 2.5] println(a) }                     | float values are
            fn main() { mut m = {"a": 1} println(m) }                       | maps are
            fn main() { mut f = fn(x: int) -> int { x } println(f(1)) }     | lambdas are
            fn main() { println(len(5)) }                                   | len() on int is
            fn main() { println(len(true)) }                                | len() on bool is
            fn main() { println(toInt(2.5)) }                               | the builtin toInt() is
            fn main() { println(parseFloat("1.5")) }                        | the builtin parseFloat() is
            fn main() -> int { return 1 }                                    | entry points returning a value are
            fn init() -> int { return 1 } fn main() { }                      | entry points returning a value are
            """)
    void unsupportedConstructIsRejected(String source, String construct) {
        assertRejected(source, construct);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            fn main() { println("a" + 1) }
            fn main() { println(1 + "a") }
            fn main() { println("a" + true) }
            fn main() { println(false + "a") }
            fn main() { println("a" + 5L + "b") }
            fn main() { mut s = "a" println(s + s) }
            fn main() { mut y = 3 println("x$y") }
            fn main() { mut y = 3L println("x${y + 1} \\$y") }
            fn main() { mut s = "a" s += "z" s += 1 println(s) }
            fn main() { println(toString(1) + toString(2L) + toString(true) + toString("s")) }
            """)
    void stringConcatenationIsSupported(String source) {
        assertEquals(List.of(), validate(source));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            fn main() { println(len("abc")) }
            fn main() { mut s = "abc" println(substring(s, 1, len(s))) }
            fn main() { println(contains("abc", "b")) }
            fn main() { println(indexOf("abc", "c")) }
            fn main() { println(startsWith("abc", "a") && endsWith("abc", "c")) }
            fn main() { println(replace("abc", "b", "x")) }
            fn main() { println(trim(" a ")) }
            fn main() { println(toUpper("a") + toLower("B")) }
            fn main() { println(chr(65) + ord("A")) }
            fn main() { println(parseInt("1") + parseLong("2")) }
            fn main() { println(toLong(1) + toInt(2L) + toInt("3")) }
            fn main() { println("a" < "b") }
            fn main() { mut a = "a" println(a <= "b" && a > "c" && a >= "d") }
            fn f(s: string) -> int { return len(trim(s)) } fn main() { println(f(" x ")) }
            """)
    void stringBuiltinsAndOrderingAreSupported(String source) {
        assertEquals(List.of(), validate(source));
    }

    @Test
    void unsupportedArgumentOfAStringBuiltinIsRejected() {
        assertRejected("fn main() { println(contains(\"a\", toString(1.5))) }", "float values are");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            fn main() { try { println(1) } catch e { println(e) } }
            fn main() { try { throw "s" } catch e: string { println(e) } }
            fn main() { try { throw 1 } catch e: int { println(e + 1) } }
            fn main() { try { throw 2L } catch e: long { println(e) } }
            fn main() { try { throw true } catch e: bool { println(!e) } }
            fn main() { try { throw null } catch e: any { println(e) } }
            fn main() { mut a: any = 3 throw a }
            fn main() { error("E1") }
            fn main() { try { error("E1") } catch e { try { throw e + "2" } catch f { println(f) } } }
            fn main() { for mut i = 0 i < 3 i = i + 1 { try { if i == 1 { continue } if i == 2 { break } } catch e { continue } } }
            fn f() -> int { try { return 1 } catch e { return 2 } } fn main() { println(f()) }
            fn f() -> int { try { 1 } catch e { 2 } } fn main() { println(f()) }
            fn f() -> int { error("x") } fn main() { println(f()) }
            fn main() { try { mut i = 0 while i < 3 { i += 1 if i == 2 { throw i } } } catch e { println(e) } }
            """)
    void exceptionsAreSupported(String source) {
        assertEquals(List.of(), validate(source));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            fn main() { try { mut a = {"a": 1} } catch e { println(e) } }          | maps are
            fn main() { try { println(1) } catch e { mut a = {"a": 1} } }          | maps are
            fn main() { try { try { println(1) } catch e { mut d = 1.5 } } catch e { } } | float values are
            fn main() { throw 1.5 }                                                | float values are
            fn main() { throw [1.5] }                                              | float values are
            fn main() { error(toString(1.5)) }                                     | float values are
            fn main() { try { println(1) } catch e: float { println(1) } }         | float values are
            fn main() { imut x = try { 1 } catch e: float { 2 } println(x) }      | float values are
            fn main() { imut x = try { 1.5 } catch e { 2.5 } println(x) }          | float values are
            """)
    void unsupportedExceptionConstructsAreRejected(String source, String construct) {
        assertRejected(source, construct);
    }

    // A try expression is accepted where the reference evaluates it on an
    // empty operand stack; elsewhere its exception handler mostly fails
    // verification (VerifyError).
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            fn main() { imut x = try { parseLong("z") } catch e { 0L - 1L } println(x) }
            fn main() { imut x = try { 1 } catch e { 2 } println(x) }
            fn main() { imut s = try { "a" } catch e { "b" + e } println(s) }
            fn main() { mut x = 0L x = try { parseLong("1") } catch e { 2L } println(x) }
            fn main() { try { parseLong("z") } catch e { println(e) } }
            fn main() { if try { 1 / 0 == 0 } catch e { false } { println(1) } }
            fn main() { throw try { "a" } catch e { "b" } }
            fn f() -> int { return try { 1 / 0 } catch e { 0 } } fn main() { println(f()) }
            fn f(x: long, s: string) -> long { return x } fn main() { imut v = f(try { 1L } catch e { 2L }, "s") println(v) }
            fn main() { imut s = toString(try { 1 } catch e { 2 }) println(s) }
            fn main() { imut l: long = try { 1 } catch e { 2 } println(l) }
            fn main() { imut v = try { try { 1 } catch e { 2 } } catch e { 3 } println(v) }
            fn main() { imut v = try { throw 1 } catch e: int { e } println(v) }
            fn main() { imut v = try { error("x") } catch e { e } println(v) }
            fn main() { imut v: string = try { mut z = 1 } catch e { "c" } println(v) }
            fn main() { imut v = try { parseLong("z") } catch e { "s" } println(v) }
            mut g = try { mut t = "a" t + "b" } catch e { "c" }
            """)
    void tryExpressionsAreSupported(String source) {
        assertEquals(List.of(), validate(source));
    }

    private static final String TRY_POSITION = "try expressions here (only as a statement, a declaration's or"
            + " assignment's value, a condition, a returned or thrown value, or the first argument of a"
            + " function or toString) are";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            fn main() { println(try { 1 } catch e { 2 }) }
            fn main() { imut x = 1L + try { 1L } catch e { 2L } println(x) }
            fn main() { imut s = "a" + try { "b" } catch e { "c" } println(s) }
            fn f(a: int, b: long) -> long { return b } fn main() { imut x = f(1, try { 1L } catch e { 2L }) println(x) }
            fn f(x: long) -> long { return x } fn main() { println(f(try { 1L } catch e { 2L })) }
            fn main() { mut x = 1L x += try { 1L } catch e { 2L } println(x) }
            fn f() -> long { return 1L } fn g(a: long, b: long) -> long { return a } fn main() { imut x = g(f(), try { 1L } catch e { 2L }) println(x) }
            fn main() { imut x = try { 1L } catch e { 2L } + 1L println(x) }
            fn main() { imut s = substring(try { "abc" } catch e { "" }, 0, 1) println(s) }
            fn main() { error(try { "a" } catch e { "b" }) }
            """)
    // The reference fails verification on all but the last three (the left
    // operand, a string builtin's and error()'s argument), which are not
    // established positions and are rejected too.
    void tryExpressionsOutsideTheEmptyStackPositionsAreRejected(String source) {
        assertRejected(source, TRY_POSITION);
    }

    @Test
    void tryExpressionBodyEndingInAVoidCallIsRejected() {
        // The reference has no value to push and fails to compile the method.
        assertRejected("fn main() { imut s: string = try { println(1) } catch e { \"c\" } println(s) }",
                "try expression bodies ending in a call with no value are");
        assertRejected("fn main() { imut s = try { \"a\" } catch e { println(e) } println(s) }",
                "try expression bodies ending in a call with no value are");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            fn main() { mut a: any = 1 println(a) }
            fn main() { mut a: any = 2L print(a) }
            fn main() { mut a: any = true println(toString(a)) }
            fn main() { mut a: any = "s" a = "t" println(a + "!") }
            fn main() { mut a: any = null a = null println("x" + a) }
            fn f(x: any) -> any { return x } fn main() { println(f(1)) }
            fn f(x: any) -> int { return x } fn main() { println(f(1) + 1) }
            fn f(x: any) -> any { return x } fn main() { imut n: int = f(1) imut l: long = f(2L) imut b: bool = f(true) imut s: string = f("s") println(s) }
            fn g(n: int, s: string) -> int { return n } fn f(x: any) -> any { return x } fn main() { println(g(f(1), f("s"))) }
            fn f(x: any) -> any { return x } fn main() { mut n = 0 n = f(3) println(n) }
            fn main() { mut a: any = 1 println(a == 1) println(a != "s") println(5L == a) println(a == true) }
            fn main() { mut a: any = 1 mut b: any = 2 println(a == b) println(a != null) }
            fn f(x: string) -> bool { return x == null } fn main() { println(f("a")) }
            fn main() { imut s: string = null println(s) println(null) }
            fn f(x: any) -> any { return x } fn main() { println(f(null) == null) }
            """)
    void anyValuesAreSupported(String source) {
        assertEquals(List.of(), validate(source));
    }

    @Test
    void anyGlobalIsSupported() {
        assertEquals(List.of(), validate("mut g: any = 5\nmut h: any = \"s\"\nfn main() { println(g) h = null println(h) }"));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            fn main() { mut x: any = 1.5 println(x) }                          | float values are
            fn main() { mut a: any = 1 println(a + 1) }                        | operator Addition on any and int is
            fn main() { mut a: any = 1 println(a < 2) }                        | operator LessThan on any and int is
            fn main() { mut a: any = true println(a && true) }                 | operator LogicalAnd on any and bool is
            fn main() { mut a: any = true println(!a) }                        | unary operator LogicalNegation on any is
            fn main() { mut a: any = true if a { println(1) } }                | conditions of type any are
            fn main() { mut a: any = true while a { println(1) } }             | conditions of type any are
            fn main() { mut a: any = "s" println(len(a)) }                     | len() on any is
            fn main() { mut a: any = "s" println(trim(a)) }                    | arguments of type any to trim() are
            fn main() { mut a: any = 1 println(toLong(a)) }                    | arguments of type any to toLong() are
            fn main() { mut a: any = 1 a = 2 println(a) }                      | assignments of int to any are
            fn main() { mut a: any = 1 a = true println(a) }                   | assignments of bool to any are
            fn f() -> any { return 7 } fn main() { println(f()) }              | returning int from a function returning any is
            fn f() -> any { return 7L } fn main() { println(f()) }             | returning long from a function returning any is
            """)
    void unsupportedUsesOfAnyAreRejected(String source, String construct) {
        assertRejected(source, construct);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            fn main() { mut a = [1, 2] println(a) }
            fn main() { mut xs: int[] = [] push(xs, 1) xs.push(2) println(len(xs)) }
            fn main() { mut ys: Array<string> = [] ys.push("a") println(ys[0]) }
            fn main() { mut xs = [1L] xs[0] = 2 xs[0] += 3L println(xs) }
            fn main() { mut xs = [true] println(xs[0] = false) }
            fn main() { mut xs = [1] println(pop(xs)) removeAt(xs, 0) xs.removeAt(0) println(xs.pop()) }
            fn main() { mut n = [[1], [2]] println(n[0][0]) n[1].push(3) n[0][0] = 4 }
            fn main() { mut a: any[] = [] push(a, 1) push(a, null) imut x: int = a[0] println(x) }
            fn f(xs: int[]) -> int[] { return xs } fn main() { println(f([1])) }
            fn f(xs: any) -> any { return xs } fn main() { imut xs: int[] = f([1]) println(xs) }
            fn main() { imut a: any = [1] imut xs: int[] = a println(a == xs) println("v=" + a) }
            fn main() { imut a: any = [[1]] println(a[0]) }
            fn main() { mut a: any = 1 a = [2] println(a) }
            fn main() { mut xs = ["a"] println("${xs}" + toString(xs)) print(xs) }
            fn main() { try { throw [1] } catch e: int[] { println(e) } }
            fn main() { imut x = try { [1] } catch e { [2] } println(x) }
            fn main() { mut xs = [[1]] push(xs, xs) println(len(xs)) }
            mut g = [1, 2]
            """)
    void arraysAreSupported(String source) {
        assertEquals(List.of(), validate(source));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            fn main() { mut xs = [3, 1] sort(xs, fn(a: int, b: int) -> int { return a - b }) }        | the builtin sort() is
            fn main() { mut xs = [3, 1] println(map(xs, fn(a: int) -> int { return a })) }           | the builtin map() is
            fn main() { mut xs = [3, 1] println(filter(xs, fn(a: int) -> bool { return a > 1 })) }   | the builtin filter() is
            fn main() { mut xs = [3, 1] println(reduce(xs, fn(a: int, b: int) -> int { return a + b }, 0)) } | the builtin reduce() is
            fn main() { mut xs = [3, 1] forEach(xs, fn(a: int) { println(a) }) }                     | the builtin forEach() is
            fn main() { imut a: any = [1] for x in a { println(x) } }                               | for … in over any values is
            fn main() { mut xs = [1] println(xs[0] + len(xs[0])) }                                  | len() on int is
            fn main() { for c in "ab" { println(len(c)) } }   | len() on any is
            fn main() { for c in "ab" { println(ord(c)) } }   | arguments of type any to ord() are
            fn main() { mut f = [1.5] println(len(f)) }                                             | float values are
            fn main() { mut xs: float[] = [] push(xs, 1.5) }                                        | float values are
            fn main() { mut xs: float[] = [] println(pop(xs)) }                                     | float values are
            fn main() { mut xs: float[] = [] println(xs[0]) }                                       | float values are
            fn main() { mut a: any = [1] println(len(a)) }                                          | len() on any is
            """)
    void unsupportedArrayConstructsAreRejected(String source, String construct) {
        assertRejected(source, construct);
    }

    // for … in is the binder's desugaring: a block declaring _colN and a for
    // loop over _idxN < len(_colN) whose body reads _colN[_idxN]. Over an
    // array, range(), split() or a string it is only len and indexing.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            fn main() { for x in [1, 2, 3] { if x == 2 { continue } if x == 3 { break } println(x) } }
            fn main() { mut xs = [1] for x in xs { if x < 3 { push(xs, x + 1) } } println(xs) }
            fn main() { for row in [[1], [2, 3]] { for x in row { println(x) } } }
            fn main() { mut a: any[] = [] push(a, "s") for x in a { println(x) } }
            fn main() { for s in ["a", "b"] { println(s + "!") } }
            fn main() { mut t = 0 for i in range(0, 10) { t = t + i } println(t) }
            fn main() { println(range(3, 0)) imut r: int[] = range(-1, 1) println(len(r)) }
            fn main() { imut parts = split("a,b,,", ",") println(len(parts)) println(parts[0] + "!") }
            fn main() { for p in split("a b", " ") { println(len(p)) } }
            fn main() { imut s = "abc" println(s[0]) println(s[len(s) - 1] + "!") }
            fn main() { for c in "héy" { println(c) } }
            fn main() { for c in "ab" { imut s: string = c println(len(s) + ord(s)) } }
            fn f(s: string, i: int) -> string { return s[i] } fn main() { println(f("ab", 1)) }
            mut g = split("x,y", ",")
            mut h = range(0, 2)
            """)
    void forInRangeSplitAndStringIndexingAreSupported(String source) {
        assertEquals(List.of(), validate(source));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            fn main() { imut m = map() for k in m { println(k) } }                    | for … in over maps is
            fn main() { imut s = set() for v in s { println(v) } }                    | for … in over sets is
            fn main() { imut a: any = "ab" for c in a { println(c) } }                | for … in over any values is
            fn main() { imut ch = channel() for m in ch { println(m) } }              | the builtin channel() is
            """)
    void forInOverOtherCollectionsIsRejected(String source, String construct) {
        assertRejected(source, construct);
    }

    // The reference writes through an erased receiver with invokeinterface,
    // which raises IncompatibleClassChangeError for a non-list; SiyoLLVM does
    // not reproduce that error, so only reads (a checkcast to List) are
    // accepted. (a[0].push(2) on an any element binds as a Java method call.)
    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            fn main() { imut a: any[] = [] push(a, [1]) a[0][0] = 2 }       | index assignment on any values is
            fn main() { imut a: any[] = [] push(a, [1]) push(a[0], 2) }     | push() on any values is
            fn main() { imut a: any[] = [] push(a, [1]) println(pop(a[0])) } | pop() on any values is
            fn main() { imut a: any[] = [] push(a, [1]) removeAt(a[0], 0) } | removeAt() on any values is
            """)
    void writesThroughAnErasedReceiverAreRejected(String source, String construct) {
        assertRejected(source, construct);
    }

    @Test
    void structDeclarationIsRejectedEvenWhenUnused() {
        assertRejected("struct P { x: int }\nfn main() { println(1) }", "struct declarations are");
    }

    @Test
    void unsupportedCodeAfterReturnIsRejected() {
        assertRejected("fn f() -> int {\n  return 1\n  mut d = 2.5\n  return 2\n}\nfn main() { println(f()) }",
                "float values are");
    }

    @Test
    void unsupportedCodeInUncalledFunctionIsRejected() {
        assertRejected("fn never() { mut a = {\"a\": 1} }\nfn main() { println(1) }", "maps are");
    }

    @Test
    void unsupportedCodeInDeadBranchIsRejected() {
        assertRejected("fn main() { if false { mut a = {\"a\": 1} } println(1) }", "maps are");
    }

    @Test
    void unsupportedGlobalInitialiserIsRejected() {
        assertRejected("mut d = 1.5\nfn main() { println(1) }", "float values are");
    }

    @Test
    void unsupportedParameterTypeIsRejected() {
        assertRejected("fn f(x: float) { }\nfn main() { println(1) }", "parameters of type float are");
    }

    @Test
    void everyViolationIsReportedWithItsLine() {
        List<SourceDiagnostic> diagnostics = validate("fn main() {\n  mut a = 1.5\n  mut b = {\"a\": 1}\n}");
        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertEquals(2, diagnostics.get(0).line());
        assertEquals(3, diagnostics.get(1).line());
    }

    @Test
    void parameterisedMainIsRejected() {
        assertRejected("fn main(x: int) { println(x) }", "entry points with parameters are");
    }
}
