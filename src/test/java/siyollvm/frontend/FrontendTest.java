package siyollvm.frontend;

import codeanalysis.FunctionSymbol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void importIsRejectedBeforeBinding() {
        FrontendResult result = Frontend.analyze("imp.siyo", "import \"std/math\"\nfn main() { println(1) }");
        assertNull(result.program());
        assertEquals(1, result.diagnostics().size());
        SourceDiagnostic d = result.diagnostics().get(0);
        assertEquals("imports are not supported by SiyoLLVM", stripPrefix(d.message()));
        assertEquals(1, d.line());
        assertEquals(1, d.column());
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
    void otherTypeDeclarationsAreStillRejectedBeforeBinding() {
        String[][] cases = {
                {"interface Named { fn name() -> string }\nfn main() { }", "interface declarations are"},
                {"type R = Good(int) | Stop\nfn main() { }", "type declarations are"},
                {"actor Store { n: int }\nfn main() { }", "actors are"},
                {"enum Color { Red, Green }\nfn main() { }", "enum declarations are"},
                {"struct P { x: int }\nimpl P { fn get(self) -> int { return self.x } }\nfn main() { }", "impl blocks are"},
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
