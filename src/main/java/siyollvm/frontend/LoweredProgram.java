package siyollvm.frontend;

import codeanalysis.FunctionSymbol;
import codeanalysis.binding.BoundBlockStatement;
import codeanalysis.text.SourceText;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bound, lowered program: what the validator checks and the emitter compiles.
 *
 * @param file      File name used in diagnostics.
 * @param text      Source text, for resolving offsets.
 * @param globals   Lowered file-level block: global declarations with their initialisers, in source order.
 * @param functions Lowered function bodies in declaration order.
 * @param declarationOffsets Source offset of each function's name, for diagnostics.
 */
public record LoweredProgram(String file, SourceText text, BoundBlockStatement globals,
                             LinkedHashMap<FunctionSymbol, BoundBlockStatement> functions,
                             Map<FunctionSymbol, Integer> declarationOffsets) {
}
