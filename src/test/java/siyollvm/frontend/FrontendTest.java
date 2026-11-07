package siyollvm.frontend;

import codeanalysis.FunctionSymbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendTest {

    @Test
    void syntaxErrorIsReportedWithLineAndColumn() {
        FrontendResult result = Frontend.analyze("bad.siyo", "fn main() {\n  mut = 1\n}");
        assertNull(result.program());
        assertFalse(result.diagnostics().isEmpty());
        SourceDiagnostic first = result.diagnostics().get(0);
        assertEquals("bad.siyo", first.file());
        assertEquals(2, first.line());
        assertTrue(first.format().startsWith("bad.siyo(2, "), first.format());
    }

    @Test
    void missingModuleIsTheBindersDiagnosticAtTheImport(@TempDir Path dir) throws Exception {
        Path main = write(dir, "imp.siyo", "fn main() { println(1) }\nimport \"nowhere/math\"\n");
        FrontendResult result = Frontend.analyze("imp.siyo", main, Files.readString(main));
        assertNull(result.program());
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        SourceDiagnostic d = result.diagnostics().get(0);
        assertEquals("imp.siyo", d.file());
        assertEquals(2, d.line());
        assertTrue(d.message().contains("nowhere/math"), d.message());
    }

    @Test
    void modulesAreUnitsInInitialisationOrderWithTheMainFileLast(@TempDir Path dir) throws Exception {
        write(dir, "d.siyo", "mut n = 1\nfn init() { }\nfn get() -> int { n }\n");
        write(dir, "lib/inner.siyo", "fn get() -> int { 2 }\n");
        write(dir, "lib/b.siyo", "import \"inner\"\nfn twice() -> int { inner.get() * 2 }\n");
        Path main = write(dir, "main.siyo",
                "import \"lib/b\"\nimport \"d\" as dd\nfn main() { println(b.twice() + dd.get() + dd.n) }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        LoweredProgram program = result.program();
        assertEquals(List.of("lib/inner.siyo", "lib/b.siyo", "d.siyo", "main.siyo"),
                program.units().stream().map(LoweredProgram.Unit::file).toList());
        LoweredProgram.Unit d = program.units().get(2);
        assertNotNull(d.init());
        assertEquals("init", d.init().getName());
        assertNull(program.units().get(1).init());
        // The main unit holds only its own functions; the imported ones resolve to the modules'.
        assertEquals(List.of("main"), program.functions().keySet().stream().map(FunctionSymbol::getName).toList());
        for (LoweredProgram.Unit unit : program.units()) {
            for (FunctionSymbol function : unit.functions().keySet()) {
                assertTrue(program.unitOf(function) == unit, function.getName());
            }
        }
        FunctionSymbol get = d.functions().keySet().stream().filter(f -> f.getName().equals("get")).findFirst().orElseThrow();
        assertTrue(program.target(get) == get);
    }

    @Test
    void diagnosticsInAModuleNameTheModuleFile(@TempDir Path dir) throws Exception {
        write(dir, "lib/util.siyo", "fn ok() -> int { 1 }\nfn bad() {\n  println(nope)\n}\n");
        Path main = write(dir, "main.siyo", "import \"lib/util\"\nfn main() { println(util.ok()) }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertNull(result.program());
        assertFalse(result.diagnostics().isEmpty());
        SourceDiagnostic d = result.diagnostics().get(0);
        assertEquals("lib/util.siyo", d.file(), result.diagnostics().toString());
        assertEquals(3, d.line());
        assertEquals(11, d.column());
        assertTrue(d.format().startsWith("lib/util.siyo(3, 11): "), d.format());
    }

    @Test
    void unsupportedDeclarationsInAModuleAreReportedInIt(@TempDir Path dir) throws Exception {
        write(dir, "shapes.siyo", "type R = Good(int) | Stop\nfn f() -> int { 1 }\n");
        Path main = write(dir, "main.siyo", "import \"shapes\"\nfn main() { println(shapes.f()) }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertNull(result.program());
        SourceDiagnostic d = result.diagnostics().get(0);
        assertEquals("shapes.siyo(1, 1): [llvm] type declarations are not supported by SiyoLLVM", d.format());
    }

    @Test
    void twoFilesWithTheSameModuleClassNameAreRejected(@TempDir Path dir) throws Exception {
        // siyoc names both Util (after the import string), so one's functions
        // and the other's globals would be mixed.
        write(dir, "util.siyo", "mut tag = \"top\"\nfn who() -> string { \"top\" }\n");
        write(dir, "lib/util.siyo", "mut tag = \"lib\"\nfn who() -> string { \"lib\" }\n");
        write(dir, "lib/b.siyo", "import \"util\"\nfn via() -> string { util.who() + util.tag }\n");
        Path main = write(dir, "main.siyo",
                "import \"util\"\nimport \"lib/b\"\nfn main() { println(util.who() + util.tag + b.via()) }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertNull(result.program());
        assertEquals(List.of("lib/b.siyo(1, 8): [llvm] two modules named Util (util.siyo, lib/util.siyo) are"
                        + " not supported by SiyoLLVM"),
                result.diagnostics().stream().map(SourceDiagnostic::format).toList());
    }

    @Test
    void oneFileImportedUnderTwoClassNamesIsRejected(@TempDir Path dir) throws Exception {
        write(dir, "lib/util.siyo", "mut tag = \"lib\"\nfn who() -> string { \"lib\" }\n");
        write(dir, "lib/b.siyo", "import \"util\"\nfn via() -> string { util.who() + util.tag }\n");
        Path main = write(dir, "main.siyo",
                "import \"lib/util\"\nimport \"lib/b\"\nfn main() { println(util.who() + b.via()) }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertNull(result.program());
        assertEquals(List.of("lib/b.siyo(1, 8): [llvm] the module lib/util.siyo imported under two names"
                        + " (Lib_Util, Util) is not supported by SiyoLLVM"),
                result.diagnostics().stream().map(SourceDiagnostic::format).toList());
    }

    @Test
    void oneFileImportedUnderTwoSpellingsOfOneNameMapsToIt(@TempDir Path dir) throws Exception {
        write(dir, "util.siyo", "mut tag = \"top\"\nfn who() -> string { \"top\" }\n");
        write(dir, "b.siyo", "import \"./util\"\nfn via() -> string { util.who() + util.tag }\n");
        Path main = write(dir, "main.siyo",
                "import \"util\"\nimport \"b\"\nfn main() { println(util.who() + b.via()) }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        LoweredProgram program = result.program();
        assertEquals(List.of("util.siyo", "b.siyo", "main.siyo"),
                program.units().stream().map(LoweredProgram.Unit::file).toList());
        FunctionSymbol who = program.units().get(0).functions().keySet().iterator().next();
        FunctionSymbol imported = new FunctionSymbol("util.who", who.getParameters(), who.getReturnType(), "Util");
        imported.setJvmMethodName("who");
        assertTrue(program.target(imported) == who);
    }

    @Test
    void anImportTheBinderDroppedSilentlyIsReported(@TempDir Path dir) throws Exception {
        // siyoc resolves x.siyo, fails to read the directory and reports nothing.
        Files.createDirectories(dir.resolve("x.siyo"));
        Path main = write(dir, "main.siyo", "import \"x\"\nfn main() { println(\"ran\") }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertNull(result.program());
        assertEquals(List.of("main.siyo(1, 8): [llvm] module 'x' could not be compiled (x.siyo)"),
                result.diagnostics().stream().map(SourceDiagnostic::format).toList());

        // Using it is the binder's error, after the import's.
        Path using = write(dir, "using.siyo", "import \"x\"\nfn main() { println(x.f()) }\n");
        FrontendResult used = Frontend.analyze("using.siyo", using, Files.readString(using));
        assertNull(used.program());
        assertEquals("using.siyo(1, 8): [llvm] module 'x' could not be compiled (x.siyo)",
                used.diagnostics().get(0).format());
        assertEquals(2, used.diagnostics().size(), used.diagnostics().toString());
    }

    @Test
    void anUnknownQualifiedSymbolFailsLoudly(@TempDir Path dir) throws Exception {
        write(dir, "util.siyo", "fn who() -> string { \"top\" }\n");
        Path main = write(dir, "main.siyo", "import \"util\"\nfn main() { println(util.who()) }\n");
        LoweredProgram program = Frontend.analyze("main.siyo", main, Files.readString(main)).program();
        FunctionSymbol who = program.units().get(0).functions().keySet().iterator().next();
        FunctionSymbol stray = new FunctionSymbol("other.who", who.getParameters(), who.getReturnType(), "Other");
        stray.setJvmMethodName("who");
        assertThrows(IllegalStateException.class, () -> program.target(stray));
        codeanalysis.VariableSymbol strayVariable = new codeanalysis.VariableSymbol("other.tag", false, String.class);
        strayVariable.setOwner("Other", "tag");
        assertThrows(IllegalStateException.class, () -> program.target(strayVariable));
    }

    @Test
    void nestedFunctionsOfOneNameInAModuleHaveDistinctKeys(@TempDir Path dir) throws Exception {
        // The binder names each nested function uniquely, so no key is
        // ambiguous; one that were would not resolve (SubsetValidatorTest).
        write(dir, "shapes.siyo", "fn n1(x: int) -> int {\n  fn inner(y: int) -> int { y * 10 }\n  return inner(x)\n}\n"
                + "fn n2(x: int) -> int {\n  fn inner(y: int) -> int { y * 100 }\n  return inner(x)\n}\n");
        Path main = write(dir, "main.siyo", "import \"shapes\"\nfn main() { println(shapes.n1(1) + shapes.n2(2)) }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        LoweredProgram program = result.program();
        assertTrue(program.ambiguousFunctions().isEmpty(), program.ambiguousFunctions().toString());
        assertEquals(4, program.units().get(0).functions().size());
        for (FunctionSymbol function : program.units().get(0).functions().keySet()) {
            assertTrue(program.target(function) == function, function.getName());
        }
    }

    @Test
    void aModuleSyntaxErrorIsReportedOnce(@TempDir Path dir) throws Exception {
        write(dir, "bad.siyo", "fn f( -> int { 1 }\n");
        Path main = write(dir, "main.siyo", "import \"bad\"\nfn main() { println(\"ran\") }\n");
        FrontendResult result = Frontend.analyze("main.siyo", main, Files.readString(main));
        assertNull(result.program());
        assertFalse(result.diagnostics().isEmpty());
        assertEquals(result.diagnostics().size(), new java.util.HashSet<>(result.diagnostics()).size(),
                result.diagnostics().toString());
    }

    private static Path write(Path dir, String name, String text) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void javaImportIsRejectedBeforeBinding() {
        FrontendResult result = Frontend.analyze("j.siyo", "import java \"java.lang.Math\"\nfn main() { println(1) }");
        assertNull(result.program());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("imports are not supported")),
                result.diagnostics().toString());
    }

    @Test
    void bindingErrorIsReported() {
        FrontendResult result = Frontend.analyze("b.siyo", "fn main() { println(undefinedName) }");
        assertNull(result.program());
        assertTrue(result.diagnostics().get(0).message().contains("undefinedName"));
    }

    @Test
    void topLevelStatementIsRejectedLikeSiyoc() {
        FrontendResult result = Frontend.analyze("t.siyo", "println(5)\nfn main() { println(6) }");
        assertNull(result.program());
        assertTrue(result.diagnostics().get(0).message().contains("top-level statement not allowed"));
    }

    @Test
    void validProgramListsFunctionsInDeclarationOrder() {
        FrontendResult result = Frontend.analyze("ok.siyo",
                "mut g = 1\nfn b() -> int { return 2 }\nfn a() { println(b()) }\nfn main() { a() }");
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        LoweredProgram program = result.program();
        assertNotNull(program);
        List<String> names = program.functions().keySet().stream().map(FunctionSymbol::getName).toList();
        assertEquals(List.of("b", "a", "main"), names);
        assertNotNull(program.globals());
        assertNotNull(program.text());
    }

    @Test
    void structDeclarationBinds() {
        FrontendResult result = Frontend.analyze("s.siyo",
                "struct P { x: int, name: string }\nfn main() { imut p = P { name: \"n\", x: 1 } println(p.x) }");
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertNotNull(result.program());
    }

    @Test
    void implMethodsAreFunctionsInDeclarationOrderUnderTheirQualifiedNames() {
        String source = "struct P { x: int }\nfn first() { }\n"
                + "impl P {\n  fn get(self) -> int { return self.x }\n  fn new(x: int) -> P { return P { x: x } }\n}\n"
                + "fn main() { println(P.new(1).get()) }";
        FrontendResult result = Frontend.analyze("i.siyo", source);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        LoweredProgram program = result.program();
        List<String> names = program.functions().keySet().stream().map(FunctionSymbol::getName).toList();
        assertEquals(List.of("first", "P.get", "P.new", "main"), names);
        for (FunctionSymbol function : program.functions().keySet()) {
            if (function.getName().equals("P.get")) {
                assertEquals(source.indexOf("get(self)"), program.declarationOffsets().get(function));
                assertEquals("self", function.getParameters().get(0).getName());
            }
        }
    }

    @Test
    void implMethodDoesNotTakeTheDeclarationOfAFunctionWithItsBareName() {
        // Same bare name and arity: each keeps its own declaration offset.
        String source = "struct P { x: int }\nimpl P { fn sum(self) -> int { return self.x } }\n"
                + "fn sum(p: P) -> int { return 2 }\nfn main() { println(sum(P { x: 1 })) }";
        FrontendResult result = Frontend.analyze("i.siyo", source);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        LoweredProgram program = result.program();
        for (FunctionSymbol function : program.functions().keySet()) {
            int offset = program.declarationOffsets().get(function);
            switch (function.getName()) {
                case "P.sum" -> assertEquals(source.indexOf("sum(self)"), offset);
                case "sum" -> assertEquals(source.indexOf("sum(p: P)"), offset);
                case "main" -> assertEquals(source.indexOf("main()"), offset);
                default -> throw new AssertionError(function.getName());
            }
        }
    }

    @Test
    void enumDeclarationBindsAndAnEnumTypeNameIsABindError() {
        FrontendResult ok = Frontend.analyze("e.siyo", "enum C { A = 10, B }\nfn main() { println(C.B) }");
        assertTrue(ok.diagnostics().isEmpty(), ok.diagnostics().toString());
        assertNotNull(ok.program());
        // The binder has no enum types: only members are values.
        FrontendResult bad = Frontend.analyze("e.siyo", "enum C { A, B }\nfn f(c: C) { }\nfn main() { }");
        assertNull(bad.program());
        assertTrue(bad.diagnostics().stream().anyMatch(d -> d.message().contains("Type 'C' does not exist")),
                bad.diagnostics().toString());
        FrontendResult missing = Frontend.analyze("e.siyo", "enum C { A, B }\nfn main() { println(C.Z) }");
        assertNull(missing.program());
        assertTrue(missing.diagnostics().get(0).message().contains("'C.Z' does not exist"),
                missing.diagnostics().toString());
    }

    @Test
    void implOfAnUndeclaredInterfaceIsABindError() {
        // Interface declarations are rejected before binding, so the interface never exists.
        FrontendResult result = Frontend.analyze("i.siyo",
                "struct P { x: int }\nimpl Named for P { fn name(self) -> string { return \"p\" } }\nfn main() { }");
        assertNull(result.program());
        assertTrue(result.diagnostics().get(0).message().contains("Type 'Named' does not exist"),
                result.diagnostics().toString());
    }

    @Test
    void otherTypeDeclarationsAreStillRejectedBeforeBinding() {
        String[][] cases = {
                {"interface Named { fn name() -> string }\nfn main() { }", "interface declarations are"},
                {"type R = Good(int) | Stop\nfn main() { }", "type declarations are"},
                {"actor Store { n: int }\nfn main() { }", "actors are"},
        };
        for (String[] c : cases) {
            FrontendResult result = Frontend.analyze("d.siyo", c[0]);
            assertNull(result.program(), c[0]);
            assertTrue(result.diagnostics().stream().anyMatch(d ->
                    stripPrefix(d.message()).equals(c[1] + " not supported by SiyoLLVM")), result.diagnostics().toString());
        }
    }

    private static String stripPrefix(String message) {
        return message.startsWith("[llvm] ") ? message.substring("[llvm] ".length()) : message;
    }
}
