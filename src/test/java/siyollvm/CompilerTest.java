package siyollvm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTest {
    @Test
    void supportedProgramGivesIr() {
        Compiler.IrResult result = Compiler.toIr("ok.siyo", "fn main() { println(1) }");
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.ir().contains("define void @siyo_program()"));
    }

    @Test
    void frontendErrorGivesNoIr() {
        Compiler.IrResult result = Compiler.toIr("bad.siyo", "fn main( {");
        assertNull(result.ir());
        assertTrue(!result.diagnostics().isEmpty());
    }

    @Test
    void unsupportedConstructGivesNoIr() {
        Compiler.IrResult result = Compiler.toIr("u.siyo", "fn main() { println(1.5) }");
        assertNull(result.ir());
        assertEquals("u.siyo(1, 21): [llvm] float values are not supported by SiyoLLVM",
                result.diagnostics().get(0).format());
    }
}
