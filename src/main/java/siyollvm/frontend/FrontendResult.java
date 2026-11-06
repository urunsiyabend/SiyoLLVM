package siyollvm.frontend;

import java.util.List;

/**
 * Outcome of {@link Frontend#analyze}: a program, or the diagnostics that prevented one.
 *
 * @param program     The lowered program; null when there are diagnostics.
 * @param diagnostics Syntax, binding or import diagnostics; empty on success.
 */
public record FrontendResult(LoweredProgram program, List<SourceDiagnostic> diagnostics) {
}
