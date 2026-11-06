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
        assertEquals(1, count(ir, "define void @siyo_program()"));
        assertFalse(ir.contains("@main"), ir);
    }

    @Test
    void stringsCarryTheirLengthSoEmbeddedNulSurvives() {
        String ir = emit("fn main() { println(\"a\\0b\") }");
        assertTrue(ir.contains("constant { i32, i32, i64, [3 x i8] } { i32 1, i32 3, i64 3, [3 x i8] c\"a\\00b\" }, align 8"), ir);
    }

    // Tag 1 (string), UTF-16 length, UTF-8 byte length, bytes: the SiyoStr layout of siyo_rt.h.
    @Test
    void literalLayout() {
        String ir = emit("fn main() { println(\"é\") }");
        assertTrue(ir.contains("{ i32 1, i32 1, i64 2, [2 x i8] c\"\\C3\\A9\" }"), ir);
    }

    // The runtime is C compiled beside the module; the IR only declares it.
    @Test
    void noRuntimeBodiesInIr() {
        String ir = emit("mut g = \"a\"\nfn f(a: int, b: int) -> int { return a / b }\n"
                + "fn main() { println(g == \"a\") print(f(4, 2)) println(true) }");
        Matcher define = Pattern.compile("(?m)^define [^@]*(@[A-Za-z0-9_.]+)\\(").matcher(ir);
        int defines = 0;
        while (define.find()) {
            defines++;
            String name = define.group(1);
            assertTrue(name.matches("@siyo\\.fn\\.\\d+") || name.equals("@siyo.init_globals")
                    || name.equals("@siyo_program"), name + " defined in\n" + ir);
        }
        assertEquals(4, defines, ir);
        assertTrue(ir.contains("@siyo_rt_stack_limit = external global i64"), ir);
        assertTrue(ir.contains("declare void @siyo_rt_print_int(i32)"), ir);
    }

    @Test
    void stringGlobalStartsNull() {
        String ir = emit("mut a = r()\nmut b = \"later\"\nfn r() -> string { return b }\nfn main() { println(a == b) }");
        assertTrue(ir.contains("internal global ptr null"), ir);
        assertTrue(ir.contains("call zeroext i1 @siyo_rt_str_eq("), ir);
    }

    @Test
    void concatenationConvertsThenConcatenates() {
        String ir = emit("fn main() { println(\"a\" + 1) }");
        int convert = ir.indexOf("call ptr @siyo_rt_str_from_int(i32 1)");
        int concat = ir.indexOf("call ptr @siyo_rt_str_concat(ptr @siyo.str.0, ptr ");
        assertTrue(convert > 0 && concat > convert, ir);
        assertTrue(ir.contains("declare ptr @siyo_rt_str_concat(ptr, ptr)"), ir);
    }

    @Test
    void toStringPicksTheConverterByType() {
        String ir = emit("fn main() { println(toString(2L)) println(toString(false)) println(toString(\"s\")) }");
        assertTrue(ir.contains("call ptr @siyo_rt_str_from_long(i64 2)"), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_str_from_bool(i1 zeroext false)"), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_str_of_string(ptr @siyo.str."), ir);
    }

    @Test
    void divisionIsGuardedAgainstZeroAndOverflow() {
        String ir = emit("fn d(a: int, b: int) -> int { return a / b }\nfn main() { println(d(1, 1)) }");
        assertTrue(ir.contains("icmp eq i32"), ir);
        assertTrue(ir.contains("call void @siyo_rt_div_by_zero()"), ir);
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
        int program = ir.indexOf("define void @siyo_program() {");
        int init = ir.indexOf("call void @siyo.fn.0()", program);
        int main = ir.indexOf("call void @siyo.fn.1()", program);
        assertTrue(program > 0 && globals > program && init > globals && main > init, ir);
    }

    @Test
    void everyUserFunctionChecksTheStack() {
        String ir = emit("fn a() { }\nfn b() { a() }\nfn main() { b() }");
        assertEquals(3, count(ir, "call void @siyo_rt_stack_overflow()"), ir);
        assertEquals(3, count(ir, "load i64, ptr @siyo_rt_stack_limit"), ir);
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
