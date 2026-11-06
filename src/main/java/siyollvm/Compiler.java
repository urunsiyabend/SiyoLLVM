package siyollvm;

import siyollvm.check.SubsetValidator;
import siyollvm.frontend.Frontend;
import siyollvm.frontend.FrontendResult;
import siyollvm.frontend.SourceDiagnostic;
import siyollvm.ir.IrEmitter;

import java.util.List;

/** Source to LLVM IR: frontend, subset validation, emission. */
public final class Compiler {
    private Compiler() {}

    /**
     * @param ir The module text; null when there are diagnostics.
     * @param diagnostics Everything that prevented IR; empty on success.
     */
    public record IrResult(String ir, List<SourceDiagnostic> diagnostics) {}

    public static IrResult toIr(String file, String source) {
        FrontendResult frontend = Frontend.analyze(file, source);
        if (frontend.program() == null) return new IrResult(null, frontend.diagnostics());
        List<SourceDiagnostic> unsupported = SubsetValidator.validate(frontend.program());
        if (!unsupported.isEmpty()) return new IrResult(null, unsupported);
        return new IrResult(IrEmitter.emit(frontend.program()), List.of());
    }
}
