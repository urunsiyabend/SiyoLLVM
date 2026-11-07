package siyollvm.frontend;

import codeanalysis.Diagnostic;
import codeanalysis.DiagnosticBox;
import codeanalysis.FunctionSymbol;
import codeanalysis.ModuleRegistry;
import codeanalysis.ModuleSymbol;
import codeanalysis.VariableSymbol;
import codeanalysis.binding.Binder;
import codeanalysis.binding.BoundBlockStatement;
import codeanalysis.binding.BoundGlobalScope;
import codeanalysis.lowering.Lowerer;
import codeanalysis.syntax.FunctionDeclarationSyntax;
import codeanalysis.syntax.ImplDeclarationSyntax;
import codeanalysis.syntax.SyntaxNode;
import codeanalysis.syntax.SyntaxTree;
import codeanalysis.syntax.SyntaxType;
import codeanalysis.text.SourceText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the SiyoCompiler frontend (snapshot) on a program: parse, bind, lower.
 *
 * <p>Binding is done exactly as {@code siyoc} binds a file (top-level
 * statements rejected), from the main file's absolute path, so the binder
 * resolves and compiles {@code import "x"} itself, beside the importing file
 * first, and keeps every module once in a {@link ModuleRegistry} in
 * dependency-first order. Java imports and the type declarations SiyoLLVM does
 * not compile (all but structs, impl blocks and enums) are rejected in the
 * main file before binding, so the binder never loads Java classes for it; in
 * a module, which the binder has already compiled, they are reported
 * afterwards, against the module's file.
 *
 * <p>An impl method is a function named {@code Type.method} whose first
 * parameter is {@code self} when it has one. The binder lowers an impl
 * method's body itself (a plain function's it leaves structured), so that
 * body is used as it is rather than lowered a second time.
 */
public final class Frontend {
    private Frontend() {}

    /**
     * Analyses a file on its own, as if it were at {@code file} relative to
     * the working directory (imports resolve there).
     *
     * @param file Name shown in diagnostics.
     * @param source The decoded source text.
     */
    public static FrontendResult analyze(String file, String source) {
        return analyze(file, Path.of(file).toAbsolutePath(), source);
    }

    /**
     * @param file Name shown in diagnostics for the main file.
     * @param path The main file's path; its imports resolve beside it, as siyoc resolves them.
     * @param source The decoded source text.
     */
    public static FrontendResult analyze(String file, Path path, String source) {
        Path absolute = path.toAbsolutePath().normalize();
        SyntaxTree tree = SyntaxTree.parse(source);
        SourceText text = tree.getText();

        List<SourceDiagnostic> diagnostics = convert(file, text, tree.diagnostics(), absolute);
        if (!diagnostics.isEmpty()) return new FrontendResult(null, diagnostics);

        findUnsupportedDeclarations(tree.getRoot(), file, text, diagnostics);
        if (!diagnostics.isEmpty()) return new FrontendResult(null, diagnostics);

        ModuleRegistry registry = new ModuleRegistry();
        BoundGlobalScope scope = Binder.bindGlobalScope(null, tree.getRoot(), registry, absolute.toString(), true);
        diagnostics = convert(file, text, scope.getDiagnostics(), absolute);
        if (!diagnostics.isEmpty()) return new FrontendResult(null, diagnostics);

        List<LoweredProgram.Unit> units = new ArrayList<>();
        Map<String, FunctionSymbol> moduleFunctions = new HashMap<>();
        Map<String, VariableSymbol> moduleVariables = new HashMap<>();
        for (ModuleSymbol module : registry.getAllModules()) {
            String display = displayName(absolute, module.getFilePath());
            String moduleSource;
            try {
                moduleSource = Files.readString(Path.of(module.getFilePath()));
            } catch (IOException | RuntimeException e) {
                diagnostics.add(new SourceDiagnostic(display, 0, 0, "cannot read the module: " + e.getMessage()));
                continue;
            }
            SyntaxTree moduleTree = SyntaxTree.parse(moduleSource);
            findUnsupportedDeclarations(moduleTree.getRoot(), display, moduleTree.getText(), diagnostics);
            BoundBlockStatement topLevel = module.getTopLevelBlock() != null
                    ? module.getTopLevelBlock() : new BoundBlockStatement(new ArrayList<>());
            LoweredProgram.Unit unit = unit(display, moduleTree, module.getFunctionBodies(), topLevel,
                    module.initFunction());
            units.add(unit);
            for (FunctionSymbol function : unit.functions().keySet()) {
                moduleFunctions.putIfAbsent(LoweredProgram.key(module.getClassName(),
                        function.getName().replace('.', '$'), function.getParameters().size()), function);
            }
            for (var entry : module.getVariables().entrySet()) {
                moduleVariables.put(module.getClassName() + "." + entry.getKey(), entry.getValue());
            }
        }
        if (!diagnostics.isEmpty()) return new FrontendResult(null, diagnostics);

        // The main file's own functions; an imported symbol's body is its module's.
        Map<FunctionSymbol, BoundBlockStatement> own = new HashMap<>();
        for (var entry : scope.getFunctionBodies().entrySet()) {
            if (entry.getKey().getModuleName() == null) own.put(entry.getKey(), entry.getValue());
        }
        FunctionSymbol init = null;
        for (FunctionSymbol function : own.keySet()) {
            if (function.getName().equals("init") && function.getParameters().isEmpty()) init = function;
        }
        units.add(unit(file, tree, own, Lowerer.lower(scope.getBoundStatement()), init));

        IdentityHashMap<FunctionSymbol, LoweredProgram.Unit> unitOf = new IdentityHashMap<>();
        for (LoweredProgram.Unit unit : units) {
            for (FunctionSymbol function : unit.functions().keySet()) unitOf.put(function, unit);
        }
        return new FrontendResult(new LoweredProgram(List.copyOf(units), unitOf, moduleFunctions, moduleVariables),
                List.of());
    }

    /**
     * A file's functions in declaration order, lowered unless the binder
     * already lowered them (impl methods).
     */
    private static LoweredProgram.Unit unit(String file, SyntaxTree tree, Map<FunctionSymbol, BoundBlockStatement> bodies,
                                            BoundBlockStatement topLevel, FunctionSymbol init) {
        Map<String, Integer> declared = new HashMap<>();
        Set<String> implMethods = new HashSet<>();
        findDeclarations(tree.getRoot(), declared, implMethods);
        Map<FunctionSymbol, Integer> offsets = new IdentityHashMap<>();
        for (FunctionSymbol function : bodies.keySet()) {
            offsets.put(function, declarationOffset(function, declared));
        }
        List<Map.Entry<FunctionSymbol, BoundBlockStatement>> entries = new ArrayList<>(bodies.entrySet());
        entries.sort(Comparator.comparingInt(e -> offsets.get(e.getKey())));
        LinkedHashMap<FunctionSymbol, BoundBlockStatement> functions = new LinkedHashMap<>();
        for (Map.Entry<FunctionSymbol, BoundBlockStatement> entry : entries) {
            FunctionSymbol function = entry.getKey();
            boolean lowered = function.getDeclarationSpan() == null && implMethods.contains(key(function));
            functions.put(function, lowered ? entry.getValue() : Lowerer.lower(entry.getValue()));
        }
        return new LoweredProgram.Unit(file, tree.getText(), topLevel, init, functions, offsets);
    }

    /**
     * How a module file is named in diagnostics: relative to the main file's
     * directory ({@code lib/util.siyo}), or as it is when it lies elsewhere.
     */
    private static String displayName(Path main, String modulePath) {
        Path module = Path.of(modulePath).toAbsolutePath().normalize();
        Path base = main.getParent();
        if (base != null && module.startsWith(base)) return base.relativize(module).toString();
        return module.toString();
    }

    /**
     * Where a function is declared. The binder records a span only for nested
     * functions; a top-level one or an impl method is found by its name
     * ({@code Type.method} for a method) and arity, which the binder keeps
     * unique.
     */
    private static int declarationOffset(FunctionSymbol function, Map<String, Integer> declared) {
        if (function.getDeclarationSpan() != null) return function.getDeclarationSpan().getStart();
        return declared.getOrDefault(key(function), Integer.MAX_VALUE);
    }

    private static String key(FunctionSymbol function) {
        return function.getName() + "/" + function.getParameters().size();
    }

    /**
     * Records each function declaration's offset by name and arity, and the
     * keys of impl methods in {@code implMethods}. A method is keyed by its
     * qualified name, as the binder names it, so it never takes the place of
     * a function with its bare name; its {@code self} counts towards the arity
     * as it does in the binder's symbol.
     */
    private static void findDeclarations(SyntaxNode node, Map<String, Integer> out, Set<String> implMethods) {
        if (node instanceof ImplDeclarationSyntax impl) {
            for (FunctionDeclarationSyntax method : impl.getMethods()) {
                String key = impl.getTypeName().getData() + "." + method.getIdentifier().getData()
                        + "/" + method.getParameters().getCount();
                out.putIfAbsent(key, method.getIdentifier().getSpan().getStart());
                implMethods.add(key);
                // Nested functions inside a method body.
                findDeclarations(method.getBody(), out, implMethods);
            }
            return;
        }
        if (node instanceof FunctionDeclarationSyntax declaration) {
            out.putIfAbsent(declaration.getIdentifier().getData() + "/" + declaration.getParameters().getCount(),
                    declaration.getIdentifier().getSpan().getStart());
        }
        for (Iterator<SyntaxNode> it = node.getChildren(); it.hasNext(); ) {
            SyntaxNode child = it.next();
            if (child != null) findDeclarations(child, out, implMethods);
        }
    }

    /**
     * Declarations SiyoLLVM cannot compile, rejected from the syntax tree: an
     * import must not reach the binder, and these type declarations bind to
     * no code the validator would see. Struct, impl and enum declarations bind
     * too, to placeholder statements; their uses (and impl methods' bodies) are
     * checked by the validator.
     */
    private static final Map<SyntaxType, String> UNSUPPORTED_DECLARATIONS = Map.of(
            SyntaxType.JavaImportStatement, "Java imports are",
            SyntaxType.TypeDeclaration, "type declarations are",
            SyntaxType.InterfaceDeclaration, "interface declarations are",
            SyntaxType.ActorDeclaration, "actors are");

    private static void findUnsupportedDeclarations(SyntaxNode node, String file, SourceText text,
                                                    List<SourceDiagnostic> out) {
        String construct = UNSUPPORTED_DECLARATIONS.get(node.getType());
        if (construct != null) {
            out.add(SourceDiagnostic.unsupported(file, text, node.getSpan().getStart(), construct));
            return;
        }
        for (Iterator<SyntaxNode> it = node.getChildren(); it.hasNext(); ) {
            SyntaxNode child = it.next();
            if (child != null) findUnsupportedDeclarations(child, file, text, out);
        }
    }

    /**
     * The binder's diagnostics; one raised in an imported module carries that
     * module's path and text, and is reported against it.
     */
    private static List<SourceDiagnostic> convert(String file, SourceText text, DiagnosticBox box, Path main) {
        List<SourceDiagnostic> result = new ArrayList<>();
        for (int i = 0; i < box.size(); i++) {
            Diagnostic d = box.get(i);
            String source = d.getSourceFile();
            boolean elsewhere = source != null && d.getSourceText() != null
                    && !Path.of(source).toAbsolutePath().normalize().equals(main);
            result.add(elsewhere
                    ? SourceDiagnostic.at(displayName(main, source), d.getSourceText(), d.getSpan().getStart(), d.toString())
                    : SourceDiagnostic.at(file, text, d.getSpan().getStart(), d.toString()));
        }
        return result;
    }
}
