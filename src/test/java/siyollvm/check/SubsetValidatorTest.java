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
            fn main() { mut s = "a" + "b" println(s) }                      | string concatenation is
            fn main() { mut n = 3 println("n=$n") }                         | string concatenation is
            fn main() { println("a" < "b") }                                | string ordering comparisons are
            fn main() { mut a = [1, 2] println(a) }                         | arrays are
            fn main() { mut f = fn(x: int) -> int { x } println(f(1)) }     | lambdas are
            fn main() { println(range(0, 3)) }                              | the builtin range() is
            fn main() -> int { return 1 }                                    | entry points returning a value are
            fn init() -> int { return 1 } fn main() { }                      | entry points returning a value are
            """)
    void unsupportedConstructIsRejected(String source, String construct) {
        assertRejected(source, construct);
    }

    @Test
    void tryCatchIsRejected() {
        assertRejected("fn main() { try { println(1) } catch e { println(2) } }", "try/catch is");
    }

    @Test
    void nullLiteralIsRejected() {
        assertRejected("fn f(x: string) -> bool { return x == null } fn main() { println(f(\"a\")) }", "null literals are");
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
