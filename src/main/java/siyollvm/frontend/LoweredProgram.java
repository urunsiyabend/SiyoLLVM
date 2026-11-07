package siyollvm.frontend;

import codeanalysis.FunctionSymbol;
import codeanalysis.VariableSymbol;
import codeanalysis.binding.BoundBlockStatement;
import codeanalysis.text.SourceText;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bound, lowered program: what the validator checks and the emitter compiles.
 *
 * <p>A program is a list of {@link Unit}s, one per source file, in the order
 * they are initialised: each imported module once, after the modules it
 * imports (the binder's registry order), and the main file last. Symbols are
 * compared by identity throughout: {@code FunctionSymbol.equals} looks at
 * name, owner and arity only, so two modules' {@code helper()} are equal.
 *
 * <p>An importer reaches a module's function through a symbol of its own
 * ({@code q.fn}, owned by the module's class; the binder gives it the same
 * body) and a module's global through a {@code q.name} variable owned by the
 * module's class. {@link #target(FunctionSymbol)} and
 * {@link #target(VariableSymbol)} map such a symbol to the module's own one,
 * which is the one a unit declares.
 *
 * @param units Every file, in initialisation order, the main file last.
 * @param unitOf The unit declaring each function (by identity).
 * @param moduleFunctions Each module's own functions by {@link #key(String, String, int)}.
 * @param moduleVariables Each module's own globals by {@code className + "." + name}.
 */
public record LoweredProgram(List<Unit> units, IdentityHashMap<FunctionSymbol, Unit> unitOf,
                             Map<String, FunctionSymbol> moduleFunctions,
                             Map<String, VariableSymbol> moduleVariables) {

    /**
     * One source file.
     *
     * @param file      File name used in diagnostics.
     * @param text      Source text, for resolving offsets.
     * @param topLevel  Lowered file-level block: global declarations with their initialisers, in source order.
     * @param init      The file's own zero-argument {@code init}, or null.
     * @param functions Lowered function bodies the file declares, in declaration order.
     * @param declarationOffsets Source offset of each function's name, for diagnostics.
     */
    public record Unit(String file, SourceText text, BoundBlockStatement topLevel, FunctionSymbol init,
                       LinkedHashMap<FunctionSymbol, BoundBlockStatement> functions,
                       Map<FunctionSymbol, Integer> declarationOffsets) {
    }

    /** The key of a module function: its module's class, its JVM method name and its arity. */
    public static String key(String className, String jvmName, int arity) {
        return className + "." + jvmName + "/" + arity;
    }

    /** The main file. */
    public Unit main() {
        return units.get(units.size() - 1);
    }

    /** The main file's name. */
    public String file() {
        return main().file();
    }

    /** The main file's text. */
    public SourceText text() {
        return main().text();
    }

    /** The main file's lowered top level. */
    public BoundBlockStatement globals() {
        return main().topLevel();
    }

    /** The main file's functions. */
    public LinkedHashMap<FunctionSymbol, BoundBlockStatement> functions() {
        return main().functions();
    }

    /** The main file's declaration offsets. */
    public Map<FunctionSymbol, Integer> declarationOffsets() {
        return main().declarationOffsets();
    }

    /** The unit declaring {@code function}, or null when no unit does (a builtin, or an imported symbol). */
    public Unit unitOf(FunctionSymbol function) {
        return unitOf.get(function);
    }

    /**
     * The function a call to {@code function} runs: itself when a unit
     * declares it, the module's own symbol for an imported one, else null.
     */
    public FunctionSymbol target(FunctionSymbol function) {
        if (unitOf.containsKey(function)) return function;
        if (function.getModuleName() == null) return null;
        String jvmName = function.getJvmMethodName() != null
                ? function.getJvmMethodName() : function.getName().replace('.', '$');
        return moduleFunctions.get(key(function.getModuleName(), jvmName, function.getParameters().size()));
    }

    /** The global a module variable {@code q.name} reads: the module's own; any other variable is itself. */
    public VariableSymbol target(VariableSymbol variable) {
        if (variable.getOwnerClass() == null) return variable;
        VariableSymbol own = moduleVariables.get(variable.getOwnerClass() + "." + variable.getFieldName());
        return own != null ? own : variable;
    }
}
