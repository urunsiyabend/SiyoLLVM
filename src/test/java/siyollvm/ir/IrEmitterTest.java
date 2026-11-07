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

@SuppressWarnings("unchecked")
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
        assertTrue(ir.contains("@siyo.str.0.bytes = private unnamed_addr constant [3 x i8] c\"a\\00b\"\n"), ir);
        assertTrue(ir.contains("@siyo.str.0 = private unnamed_addr constant { i32, i32, i64, ptr, ptr } "
                + "{ i32 1, i32 3, i64 3, ptr @siyo.str.0.bytes, ptr null }, align 8"), ir);
    }

    // Tag 1 (string), UTF-16 length, UTF-8 byte length, data pointer, no buffer:
    // the SiyoStr layout of siyo_rt.h. The bytes are a constant of their own.
    @Test
    void literalLayout() {
        String ir = emit("fn main() { println(\"é\") }");
        assertTrue(ir.contains("@siyo.str.0.bytes = private unnamed_addr constant [2 x i8] c\"\\C3\\A9\"\n"), ir);
        assertTrue(ir.contains("{ i32 1, i32 1, i64 2, ptr @siyo.str.0.bytes, ptr null }"), ir);
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
    void stringOrderingComparesWithCompareTo() {
        String ir = emit("fn main() { mut a = \"a\" println(a < \"b\") println(a >= \"b\") }");
        assertEquals(2, count(ir, "call i32 @siyo_rt_str_compare(ptr "), ir);
        assertTrue(ir.contains(", 0\n") && ir.contains("icmp slt i32 ") && ir.contains("icmp sge i32 "), ir);
        assertTrue(ir.contains("declare i32 @siyo_rt_str_compare(ptr, ptr)"), ir);
    }

    @Test
    void numericConversionsAreInline() {
        String ir = emit("fn main() { mut i = 7 mut l = 8L println(toLong(i)) println(toInt(l)) }");
        assertTrue(ir.contains("sext i32 "), ir);
        assertTrue(ir.contains("trunc i64 "), ir);
    }

    @Test
    void stringBuiltinsCallTheRuntime() {
        String ir = emit("""
                fn main() {
                  mut s = "abc"
                  println(len(s))
                  println(substring(s, 0, 1))
                  println(contains(s, "b"))
                  println(startsWith(s, "a"))
                  println(chr(65))
                  println(toInt("1") + parseInt("2"))
                  println(parseLong("3"))
                }
                """);
        assertTrue(ir.contains("call i32 @siyo_rt_str_len(ptr "), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_str_substring(ptr %t"), ir);
        assertTrue(ir.contains(", i32 0, i32 1)"), ir);
        assertTrue(ir.contains("call zeroext i1 @siyo_rt_str_contains(ptr "), ir);
        assertTrue(ir.contains("call zeroext i1 @siyo_rt_str_starts_with(ptr "), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_chr(i32 65)"), ir);
        assertEquals(2, count(ir, "call i32 @siyo_rt_parse_int(ptr "), ir);
        assertTrue(ir.contains("call i64 @siyo_rt_parse_long(ptr "), ir);
    }

    @Test
    void userFunctionNamedLikeABuiltinIsNotTheBuiltin() {
        String ir = emit("fn trim(s: string) -> string { return s }\nfn main() { println(trim(\" a \")) }");
        assertFalse(ir.contains("call ptr @siyo_rt_str_trim("), ir);
        assertTrue(ir.contains("notail call ptr @siyo.fn.0(ptr "), ir);
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

    // An any value is a pointer to a tagged heap object (a string is one already) or null.
    @Test
    void anyIsAPointerAndPrimitivesAreBoxedIntoIt() {
        String ir = emit("mut g: any = 1\nfn f(x: any) -> any { return x }\n"
                + "fn main() { println(f(2L)) print(f(true)) println(f(\"s\")) println(f(null)) println(g) }");
        assertTrue(ir.contains("@siyo.g.0 = internal global ptr null"), ir);
        assertTrue(ir.contains("define internal ptr @siyo.fn.0(ptr %p0)"), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_box_int(i32 1)"), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_box_long(i64 2)"), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_box_bool(i1 zeroext true)"), ir);
        assertTrue(ir.contains("notail call ptr @siyo.fn.0(ptr @siyo.str.0)"), ir);
        assertTrue(ir.contains("notail call ptr @siyo.fn.0(ptr null)"), ir);
        assertEquals(5, count(ir, "call void @siyo_rt_print_any(ptr "), ir);
    }

    @Test
    void anyIsUnboxedOrCastWithACheck() {
        String ir = emit("fn f(x: any) -> any { return x }\nfn r(x: any) -> bool { return x }\n"
                + "fn main() { imut n: int = f(1) imut l: long = f(2L) imut s: string = f(\"s\") println(r(true))"
                + " mut m = 0 m = f(3) println(n + l + m + s) }");
        assertTrue(ir.contains("call i32 @siyo_rt_unbox_int(ptr "), ir);
        assertTrue(ir.contains("call i64 @siyo_rt_unbox_long(ptr "), ir);
        assertTrue(ir.contains("call zeroext i1 @siyo_rt_unbox_bool(ptr "), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_cast_str(ptr "), ir);
        assertEquals(2, count(ir, "call i32 @siyo_rt_unbox_int(ptr "), ir);
    }

    @Test
    void equalityWithAnAnyOperandIsObjectsEquals() {
        String ir = emit("fn f(x: any) -> any { return x }\n"
                + "fn main() { println(f(5L) == 5) println(f(\"a\") != \"a\") println(f(1) == f(1)) }");
        assertEquals(3, count(ir, "call zeroext i1 @siyo_rt_any_equals(ptr "), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_box_int(i32 5)"), ir);
        assertFalse(ir.contains("call zeroext i1 @siyo_rt_str_eq("), ir);
    }

    @Test
    void anyInTextIsStringValueOf() {
        String ir = emit("fn f(x: any) -> any { return x }\nfn main() { println(\"a\" + f(1)) println(toString(f(2))) }");
        assertEquals(2, count(ir, "call ptr @siyo_rt_any_to_string(ptr "), ir);
        assertTrue(ir.contains("call ptr @siyo_rt_str_concat(ptr @siyo.str.0, ptr "), ir);
    }

    // A raise sets the runtime's pending flag and returns; each call that can
    // raise is followed by a check that branches to the innermost catch block.
    @Test
    void callInsideTryBranchesToTheCatchBlock() {
        String ir = emit("fn g() -> int { return 1 }\n"
                + "fn main() { try { println(g()) } catch e { println(e) } }");
        String main = function(ir, "@siyo.fn.1");
        Matcher check = Pattern.compile("notail call i32 @siyo\\.fn\\.0\\(\\)\n"
                + "  (%t\\d+) = load i8, ptr @siyo_rt_pending_flag\n"
                + "  (%t\\d+) = icmp ne i8 \\1, 0\n"
                + "  br i1 \\2, label %(catch\\.\\d+), label %").matcher(main);
        assertTrue(check.find(), main);
        assertTrue(main.contains("\n" + check.group(3) + ":\n  %t"), main);
        assertTrue(Pattern.compile("\n" + Pattern.quote(check.group(3)) + ":\n  %t\\d+ = call ptr @siyo_rt_catch\\(\\)")
                .matcher(main).find(), main);
        assertFalse(main.contains("label %unwind"), main);
    }

    @Test
    void callOutsideTryReturnsTheDefaultToTheCaller() {
        String ir = emit("fn g() -> int { return 1 }\nfn f() -> long { return g() }\nfn main() { println(f()) }");
        String f = function(ir, "@siyo.fn.1");
        assertTrue(f.contains(", label %unwind, label %"), f);
        assertTrue(f.contains("\nunwind:\n  ret i64 0\n"), f);
        // A function with no raising call has no unwind block.
        assertFalse(function(ir, "@siyo.fn.0").contains("unwind"), ir);
    }

    @Test
    void theProgramReportsAnExceptionLeftPendingByAnEntryPoint() {
        String ir = emit("mut g = 1\nfn init() { g = 2 }\nfn main() { println(g) }");
        String program = function(ir, "@siyo_program");
        assertEquals(3, count(program, "load i8, ptr @siyo_rt_pending_flag"), program);
        assertTrue(program.contains("call void @siyo_rt_uncaught()\n  unreachable"), program);
        assertTrue(ir.contains("declare void @siyo_rt_uncaught() noreturn cold"), ir);
        assertTrue(ir.contains("@siyo_rt_pending_flag = external thread_local global i8"), ir);
    }

    @Test
    void throwBoxesThePayloadAndBranchesToTheHandler() {
        String ir = emit("fn f() { throw 5 }\nfn main() { try { f() } catch e { println(e) } }");
        String f = function(ir, "@siyo.fn.0");
        assertTrue(Pattern.compile("(%t\\d+) = call ptr @siyo_rt_box_int\\(i32 5\\)\n"
                + "  call void @siyo_rt_throw\\(ptr \\1\\)\n  br label %unwind\n").matcher(f).find(), f);
    }

    @Test
    void errorThrowsItsArgument() {
        String ir = emit("fn main() { error(\"E1\") }");
        String main = function(ir, "@siyo.fn.0");
        assertTrue(main.contains("call void @siyo_rt_throw(ptr @siyo.str.0)\n  br label %unwind\n"), main);
    }

    @Test
    void onlyRaisingRuntimeCallsAreChecked() {
        String ir = emit("fn main() { mut s = \"abc\" println(s + substring(s, 0, 1)) println(parseInt(s)) }");
        String main = function(ir, "@siyo.fn.0");
        // substring raises; concatenation, parseInt and printing do not.
        assertEquals(1, count(main, "load i8, ptr @siyo_rt_pending_flag"), main);
    }

    @Test
    void divisionByZeroRaisesAndBranchesToTheHandler() {
        String ir = emit("fn d(a: int, b: int) -> int { try { return a / b } catch e { return -1 } }\n"
                + "fn main() { println(d(1, 0)) }");
        String d = function(ir, "@siyo.fn.0");
        assertTrue(Pattern.compile("call void @siyo_rt_div_by_zero\\(\\)\n  br label %catch\\.\\d+\n").matcher(d).find(), d);
        assertTrue(ir.contains("declare void @siyo_rt_div_by_zero() cold\n"), ir);
    }

    @Test
    void catchVariableIsCastToItsDeclaredTypeWithACheck() {
        String ir = emit("fn main() { try { throw 1 } catch e: int { println(e) } }");
        String main = function(ir, "@siyo.fn.0");
        Matcher cast = Pattern.compile("(%t\\d+) = call ptr @siyo_rt_catch\\(\\)\n"
                + "  %t\\d+ = call i32 @siyo_rt_unbox_int\\(ptr \\1\\)\n"
                + "  %t\\d+ = load i8, ptr @siyo_rt_pending_flag\n").matcher(main);
        assertTrue(cast.find(), main);
    }

    @Test
    void nullLiteralUnboxedToIntOrBoolRaisesTheJvmMessage() {
        String ir = emit("fn g(b: bool) { println(b) }\nfn main() { imut n: int = null println(n) g(null) }");
        String main = function(ir, "@siyo.fn.1");
        assertTrue(main.contains("call void @siyo_rt_unbox_null_literal(i32 2)\n  br label %unwind\n"), main);
        assertTrue(main.contains("call void @siyo_rt_unbox_null_literal(i32 4)\n  br label %unwind\n"), main);
    }

    @Test
    void jumpsOutOfTryAndCatchAreWellFormed() {
        emit("""
                fn f(n: int) -> int {
                  mut total = 0
                  for mut i = 0 i < n i = i + 1 {
                    try {
                      if i == 1 { continue }
                      if i == 5 { break }
                      if i == 3 { throw i }
                      if i == 4 { return total }
                      total += i
                    } catch e: int {
                      if e == 3 { continue }
                      return -1
                    }
                  }
                  return total
                }
                fn main() { println(f(9)) }
                """);
    }

    // A try expression's bodies each store their tail value into one slot of
    // the expression's type, which the join block loads.
    @Test
    void tryExpressionJoinsBothBodiesThroughASlot() {
        String ir = emit("fn main() { imut v = try { parseLong(\"z\") } catch e { 0L - 1L } println(v) }");
        String main = function(ir, "@siyo.fn.0");
        Matcher slot = Pattern.compile("(%t\\d+) = alloca i64\n").matcher(main);
        assertTrue(slot.find(), main);
        String s = Pattern.quote(slot.group(1));
        Matcher parse = Pattern.compile("(%t\\d+) = call i64 @siyo_rt_parse_long\\(ptr @siyo\\.str\\.0\\)\n"
                + "  %t\\d+ = load i8, ptr @siyo_rt_pending_flag\n"
                + "  %t\\d+ = icmp ne i8 %t\\d+, 0\n"
                + "  br i1 %t\\d+, label %(catch\\.\\d+), label %(ok\\.\\d+)\n"
                + "\\3:\n"
                + "  store i64 \\1, ptr " + s + "\n"
                + "  br label %(try\\.value\\.\\d+)\n").matcher(main);
        assertTrue(parse.find(), main);
        assertTrue(Pattern.compile("\n" + Pattern.quote(parse.group(2)) + ":\n  %t\\d+ = call ptr @siyo_rt_catch\\(\\)\n")
                .matcher(main).find(), main);
        assertTrue(Pattern.compile("  (%t\\d+) = sub i64 0, 1\n  store i64 \\1, ptr " + s + "\n  br label %"
                + Pattern.quote(parse.group(4)) + "\n").matcher(main).find(), main);
        assertTrue(Pattern.compile("\n" + Pattern.quote(parse.group(4)) + ":\n  %t\\d+ = load i64, ptr " + s + "\n")
                .matcher(main).find(), main);
    }

    // As on the JVM, the value is narrowed after the join: a failing cast
    // raises to the enclosing handler, not to the expression's own catch.
    @Test
    void tryExpressionNarrowsItsValueOutsideItsOwnHandler() {
        String ir = emit("fn main() { try { imut v = try { parseLong(\"x\") } catch e { \"s\" } println(v) }"
                + " catch outer { println(outer) } }");
        String main = function(ir, "@siyo.fn.0");
        // The try statement's catch block is catch.0; the expression's is a later one.
        assertTrue(Pattern.compile("call i64 @siyo_rt_unbox_long\\(ptr @siyo\\.str\\.\\d+\\)\n"
                + "  %t\\d+ = load i8, ptr @siyo_rt_pending_flag\n"
                + "  %t\\d+ = icmp ne i8 %t\\d+, 0\n"
                + "  br i1 %t\\d+, label %catch\\.0, ").matcher(main).find(), main);
        assertTrue(Pattern.compile("@siyo_rt_parse_long[^\\n]*\n[^\\n]*\n[^\\n]*\n  br i1 %t\\d+, label %catch\\.[1-9]")
                .matcher(main).find(), main);
    }

    // A body ending in a statement gives null, which the JVM unboxes after the
    // join: the runtime's message, not the null literal's.
    @Test
    void tryExpressionBodyWithoutAValueGivesNull() {
        String ir = emit("fn main() { imut n = try { mut z = 1 } catch e { 5 } println(n) }");
        String main = function(ir, "@siyo.fn.0");
        assertTrue(main.contains("call i32 @siyo_rt_unbox_int(ptr null)\n"), main);
        assertFalse(main.contains("unbox_null_literal"), main);
    }

    @Test
    void tryExpressionInAGlobalInitialiserKeepsItsLocalsInSlots() {
        String ir = emit("mut g = try { mut t = \"a\" t + \"b\" } catch e { \"c\" }\nfn main() { println(g) }");
        String init = function(ir, "@siyo.init_globals");
        assertFalse(init.contains("ptr null\n"), init);
        assertTrue(Pattern.compile("store ptr %t\\d+, ptr @siyo\\.g\\.0\n").matcher(init).find(), init);
    }

    @Test
    void refusesNodesItDoesNotKnow() {
        FrontendResult result = Frontend.analyze("e.siyo", "fn main() { mut a = [1] }");
        assertThrows(IllegalStateException.class, () -> IrEmitter.emit(result.program()));
    }

    /**
     * No valid source reaches text() with a non-string reference type (the
     * binder rejects {@code "a" + [1]} and the emitter refuses the array
     * literal first), so this calls the private method by reflection with a
     * value of an unsupported type to pin its contract: refuse, naming the type.
     */
    @Test
    void refusesToConvertNonStringReferenceTypesToText() throws Exception {
        FrontendResult result = Frontend.analyze("e.siyo", "fn main() { }");
        java.lang.reflect.Constructor<IrEmitter> ctor = IrEmitter.class.getDeclaredConstructors().length == 1
                ? (java.lang.reflect.Constructor<IrEmitter>) IrEmitter.class.getDeclaredConstructors()[0] : null;
        ctor.setAccessible(true);
        IrEmitter emitter = ctor.newInstance(result.program());
        Class<?> valueClass = Class.forName("siyollvm.ir.IrEmitter$Value");
        java.lang.reflect.Constructor<?> valueCtor = valueClass.getDeclaredConstructor(String.class, Class.class);
        valueCtor.setAccessible(true);
        Object value = valueCtor.newInstance("%v", Object[].class);
        java.lang.reflect.Method text = IrEmitter.class.getDeclaredMethod("text", valueClass);
        text.setAccessible(true);
        java.lang.reflect.InvocationTargetException e =
                assertThrows(java.lang.reflect.InvocationTargetException.class, () -> text.invoke(emitter, value));
        assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
        assertTrue(e.getCause().getMessage().contains("conversion of " + Object[].class), e.getCause().getMessage());
    }

    /** The definition of {@code name}, from {@code define} to its closing brace. */
    private static String function(String ir, String name) {
        Matcher define = Pattern.compile("(?m)^define [^\\n]* " + Pattern.quote(name) + "\\(").matcher(ir);
        assertTrue(define.find(), name + " not defined in\n" + ir);
        return ir.substring(define.start(), ir.indexOf("\n}\n", define.start()) + 2);
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
