package siyollvm.ir;

import org.junit.jupiter.api.Test;
import siyollvm.check.SubsetValidator;
import siyollvm.frontend.Frontend;
import siyollvm.frontend.FrontendResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrEmitterTest {

    static String emit(String source) {
        FrontendResult result = Frontend.analyze("e.siyo", source);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertEquals(java.util.List.of(), SubsetValidator.validate(result.program()));
        String ir = IrEmitter.emit(result.program());
        IrStructure.assertWellFormed(ir);
        return ir;
    }

    @Test
    void usesOpaquePointersOnly() {
        String ir = emit("fn main() { println(\"x\") }");
        assertFalse(ir.contains("i8*"), ir);
        assertTrue(ir.contains("ptr"), ir);
    }

    @Test
    void unicodeParameterNamesGetDistinctLlvmNames() {
        String ir = emit("fn sum(ü: int, ö: int) -> int { return ü + ö }\nfn main() { println(sum(2, 3)) }");
        assertTrue(ir.contains("define internal i32 @siyo.fn.0(i32 %p0, i32 %p1)"), ir);
        assertFalse(ir.contains("ü"), "source identifiers must not reach LLVM names");
    }

    @Test
    void userFunctionNamesCannotCollideWithLibc() {
        String ir = emit("fn printf() -> int { return 1 }\nfn exit() { }\nfn main() { exit() println(printf()) }");
        assertTrue(ir.contains("define internal i32 @siyo.fn.0()"), ir);
        assertTrue(ir.contains("define internal void @siyo.fn.1()"), ir);
        assertEquals(1, count(ir, "define i32 @main()"));
    }

    @Test
    void stringsCarryTheirLengthSoEmbeddedNulSurvives() {
        String ir = emit("fn main() { println(\"a\\0b\") }");
        assertTrue(ir.contains("constant { i64, [3 x i8] } { i64 3, [3 x i8] c\"a\\00b\" }"), ir);
    }

    @Test
    void nonAsciiStringIsEncodedAsUtf8Bytes() {
        String ir = emit("fn main() { println(\"é\") }");
        assertTrue(ir.contains("{ i64 2, [2 x i8] c\"\\C3\\A9\" }"), ir);
    }

    @Test
    void stringGlobalStartsNull() {
        String ir = emit("mut a = r()\nmut b = \"later\"\nfn r() -> string { return b }\nfn main() { println(a == b) }");
        assertTrue(ir.contains("internal global ptr null"), ir);
        assertTrue(ir.contains("call i1 @siyo.str_eq("), ir);
    }

    @Test
    void divisionIsGuardedAgainstZeroAndOverflow() {
        String ir = emit("fn d(a: int, b: int) -> int { return a / b }\nfn main() { println(d(1, 1)) }");
        assertTrue(ir.contains("icmp eq i32"), ir);
        assertTrue(ir.contains("call void @siyo.div_by_zero()"), ir);
        assertTrue(ir.contains("select i1"), ir);
        assertTrue(Pattern.compile("sdiv i32 %t\\d+, %t\\d+").matcher(ir).find(), ir);
    }

    @Test
    void shiftCountIsMasked() {
        String ir = emit("fn s(a: int, b: int) -> int { return a << b }\nfn main() { println(s(1, 33)) }");
        assertTrue(ir.contains(", 31"), ir);
        assertTrue(ir.contains("shl i32"), ir);
    }

    @Test
    void mainRunsGlobalsThenInitThenMain() {
        String ir = emit("mut g = 1\nfn init() { g = 2 }\nfn main() { println(g) }");
        int globals = ir.indexOf("call void @siyo.init_globals()");
        int init = ir.indexOf("call void @siyo.fn.0()", ir.indexOf("define internal ptr @siyo.program("));
        int main = ir.indexOf("call void @siyo.fn.1()", ir.indexOf("define internal ptr @siyo.program("));
        assertTrue(globals > 0 && init > globals && main > init, ir);
        assertTrue(ir.contains("call void @siyo.run_program_thread()"), ir);
    }

    @Test
    void everyUserFunctionChecksTheStack() {
        String ir = emit("fn a() { }\nfn b() { a() }\nfn main() { b() }");
        assertEquals(3, count(ir, "call void @siyo.stack_overflow()"), ir);
    }

    @Test
    void fallingOffAValueFunctionReturnsTheDefault() {
        String ir = emit("fn f(x: int) -> int { if x > 0 { return 1 } }\nfn main() { println(f(0)) }");
        int start = ir.indexOf("define internal i32 @siyo.fn.0(");
        String f = ir.substring(start, ir.indexOf("\n}\n", start) + 2);
        assertTrue(f.endsWith("  ret i32 0\n}"), f);
    }

    @Test
    void shortCircuitUsesBranches() {
        String ir = emit("fn t() -> bool { return true }\nfn main() { println(false && t()) }");
        assertTrue(ir.contains("phi i1"), ir);
    }

    // Regression (independent review): a short-circuit after a return started a
    // dead block, but the phi named the block before it.
    @Test
    void deadShortCircuitAfterReturnIsWellFormed() {
        String ir = emit("fn f() -> bool { return true return false && true } fn main() { println(f()) }");
        assertTrue(ir.contains("phi i1"), ir);
    }

    @Test
    void nestedDeadShortCircuitIsWellFormed() {
        emit("fn f() -> bool { return true return (false && true) || (true && !false) } fn main() { println(f()) }");
    }

    @Test
    void deadShortCircuitInAConditionIsWellFormed() {
        emit("fn f(x: bool) -> int { return 1 if x || false { return 2 } while true && x { return 3 } return 4 }"
                + " fn main() { println(f(true)) }");
    }

    @Test
    void deadDivisionAndAssignmentAreWellFormed() {
        emit("fn f(a: int) -> int { return 2 mut b = a / 0 b = b % a return b } fn main() { println(f(1)) }");
    }

    @Test
    void refusesNodesItDoesNotKnow() {
        FrontendResult result = Frontend.analyze("e.siyo", "fn main() { mut a = [1] }");
        assertThrows(IllegalStateException.class, () -> IrEmitter.emit(result.program()));
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
