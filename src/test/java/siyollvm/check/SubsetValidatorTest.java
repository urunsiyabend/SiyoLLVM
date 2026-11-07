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
            fn main() { mut a = [1, 2] println(a) }                         | arrays are
            fn main() { mut f = fn(x: int) -> int { x } println(f(1)) }     | lambdas are
            fn main() { println(range(0, 3)) }                              | the builtin range() is
            fn main() { println(split("a,b", ",")) }                        | the builtin split() is
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

    @Test
    void tryCatchIsRejected() {
        assertRejected("fn main() { try { println(1) } catch e { println(2) } }", "try/catch is");
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
        assertRejected("fn never() { mut a = [1] }\nfn main() { println(1) }", "arrays are");
    }

    @Test
    void unsupportedCodeInDeadBranchIsRejected() {
        assertRejected("fn main() { if false { mut a = [1] } println(1) }", "arrays are");
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
        List<SourceDiagnostic> diagnostics = validate("fn main() {\n  mut a = 1.5\n  mut b = [1]\n}");
        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertEquals(2, diagnostics.get(0).line());
        assertEquals(3, diagnostics.get(1).line());
    }

    @Test
    void parameterisedMainIsRejected() {
        assertRejected("fn main(x: int) { println(x) }", "entry points with parameters are");
    }
}
