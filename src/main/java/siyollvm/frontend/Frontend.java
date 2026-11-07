package siyollvm.frontend;

import codeanalysis.Diagnostic;
import codeanalysis.DiagnosticBox;
import codeanalysis.FunctionSymbol;
import codeanalysis.ModuleRegistry;
import codeanalysis.binding.Binder;
import codeanalysis.binding.BoundBlockStatement;
import codeanalysis.binding.BoundGlobalScope;
import codeanalysis.lowering.Lowerer;
import codeanalysis.syntax.FunctionDeclarationSyntax;
import codeanalysis.syntax.SyntaxNode;
import codeanalysis.syntax.SyntaxTree;
import codeanalysis.syntax.SyntaxType;
import codeanalysis.text.SourceText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the SiyoCompiler frontend (snapshot) on one file: parse, bind, lower.
 *
 * <p>Binding is done exactly as {@code siyoc} binds a file (top-level
 * statements rejected). Imports and the type declarations SiyoLLVM does not
 * compile (all but structs) are rejected before binding, so the binder never
 * resolves modules from the filesystem or Java classes from the classpath.
 */
public final class Frontend {
    private Frontend() {}

    /**
     * @param file Name shown in diagnostics.
     * @param source The decoded source text.
     */
    public static FrontendResult analyze(String file, String source) {
        SyntaxTree tree = SyntaxTree.parse(source);
        SourceText text = tree.getText();

        List<SourceDiagnostic> diagnostics = convert(file, text, tree.diagnostics());
        if (!diagnostics.isEmpty()) return new FrontendResult(null, diagnostics);

        findUnsupportedDeclarations(tree.getRoot(), file, text, diagnostics);
        if (!diagnostics.isEmpty()) return new FrontendResult(null, diagnostics);

        BoundGlobalScope scope = Binder.bindGlobalScope(null, tree.getRoot(), new ModuleRegistry(), file, true);
        diagnostics = convert(file, text, scope.getDiagnostics());
        if (!diagnostics.isEmpty()) return new FrontendResult(null, diagnostics);

        Map<String, Integer> declared = new HashMap<>();
        findDeclarations(tree.getRoot(), declared);
        Map<FunctionSymbol, Integer> offsets = new HashMap<>();
        for (FunctionSymbol function : scope.getFunctionBodies().keySet()) {
            offsets.put(function, declarationOffset(function, declared));
        }
        List<Map.Entry<FunctionSymbol, BoundBlockStatement>> bodies = new ArrayList<>(scope.getFunctionBodies().entrySet());
        bodies.sort(Comparator.comparingInt(e -> offsets.get(e.getKey())));
        LinkedHashMap<FunctionSymbol, BoundBlockStatement> functions = new LinkedHashMap<>();
        for (Map.Entry<FunctionSymbol, BoundBlockStatement> entry : bodies) {
            functions.put(entry.getKey(), Lowerer.lower(entry.getValue()));
        }
        BoundBlockStatement globals = Lowerer.lower(scope.getBoundStatement());
        return new FrontendResult(new LoweredProgram(file, text, globals, functions, offsets), List.of());
    }

    /**
     * Where a function is declared. The binder records a span only for nested
     * functions; a top-level one is found by name and arity, which the binder
     * keeps unique.
     */
    private static int declarationOffset(FunctionSymbol function, Map<String, Integer> declared) {
        if (function.getDeclarationSpan() != null) return function.getDeclarationSpan().getStart();
        return declared.getOrDefault(function.getName() + "/" + function.getParameters().size(), Integer.MAX_VALUE);
    }

    private static void findDeclarations(SyntaxNode node, Map<String, Integer> out) {
        if (node instanceof FunctionDeclarationSyntax declaration) {
            out.putIfAbsent(declaration.getIdentifier().getData() + "/" + declaration.getParameters().getCount(),
                    declaration.getIdentifier().getSpan().getStart());
        }
        for (Iterator<SyntaxNode> it = node.getChildren(); it.hasNext(); ) {
            SyntaxNode child = it.next();
            if (child != null) findDeclarations(child, out);
        }
    }

    /**
     * Declarations SiyoLLVM cannot compile, rejected from the syntax tree: an
     * import must not reach the binder, and these type declarations bind to
     * no code the validator would see. A struct declaration binds too, to a
     * placeholder statement; its uses are checked by the validator.
     */
    private static final Map<SyntaxType, String> UNSUPPORTED_DECLARATIONS = Map.of(
            SyntaxType.ImportStatement, "imports are",
            SyntaxType.JavaImportStatement, "imports are",
            SyntaxType.EnumDeclaration, "enum declarations are",
            SyntaxType.TypeDeclaration, "type declarations are",
            SyntaxType.InterfaceDeclaration, "interface declarations are",
            SyntaxType.ImplDeclaration, "impl blocks are",
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

    private static List<SourceDiagnostic> convert(String file, SourceText text, DiagnosticBox box) {
        List<SourceDiagnostic> result = new ArrayList<>();
        for (int i = 0; i < box.size(); i++) {
            Diagnostic d = box.get(i);
            result.add(SourceDiagnostic.at(file, text, d.getSpan().getStart(), d.toString()));
        }
        return result;
    }
}
