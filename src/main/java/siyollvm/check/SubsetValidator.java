package siyollvm.check;

import codeanalysis.BuiltinFunctions;
import codeanalysis.FunctionSymbol;
import codeanalysis.ParameterSymbol;
import codeanalysis.binding.BoundAssignmentExpression;
import codeanalysis.binding.BoundBinaryExpression;
import codeanalysis.binding.BoundBinaryOperatorType;
import codeanalysis.binding.BoundBlockStatement;
import codeanalysis.binding.BoundCallExpression;
import codeanalysis.binding.BoundConditionalGotoStatement;
import codeanalysis.binding.BoundConversionExpression;
import codeanalysis.binding.BoundExpression;
import codeanalysis.binding.BoundExpressionStatement;
import codeanalysis.binding.BoundGotoStatement;
import codeanalysis.binding.BoundLabelStatement;
import codeanalysis.binding.BoundLiteralExpression;
import codeanalysis.binding.BoundNode;
import codeanalysis.binding.BoundNodeType;
import codeanalysis.binding.BoundReturnStatement;
import codeanalysis.binding.BoundStatement;
import codeanalysis.binding.BoundUnaryExpression;
import codeanalysis.binding.BoundUnaryOperatorType;
import codeanalysis.binding.BoundVariableDeclaration;
import codeanalysis.binding.BoundVariableExpression;
import siyollvm.frontend.LoweredProgram;
import siyollvm.frontend.SourceDiagnostic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a lowered program lies inside the subset SiyoLLVM compiles.
 *
 * <p>This is a whitelist: every statement and expression of every function —
 * called or not, reachable or not — and of the global initialiser block is
 * checked, and anything not listed here is reported. The emitter only runs
 * on programs this accepts, so a construct the backend does not implement
 * can never be compiled into something else.
 */
public final class SubsetValidator {

    private static final Set<Class<?>> VALUE_TYPES = Set.of(Integer.class, Long.class, Boolean.class, String.class);

    private static final Set<BoundBinaryOperatorType> ARITHMETIC = EnumSet.of(
            BoundBinaryOperatorType.Addition, BoundBinaryOperatorType.Subtraction,
            BoundBinaryOperatorType.Multiplication, BoundBinaryOperatorType.Division, BoundBinaryOperatorType.Modulo);
    private static final Set<BoundBinaryOperatorType> COMPARISON = EnumSet.of(
            BoundBinaryOperatorType.Equals, BoundBinaryOperatorType.NotEquals,
            BoundBinaryOperatorType.LessThan, BoundBinaryOperatorType.LessOrEqualsThan,
            BoundBinaryOperatorType.GreaterThan, BoundBinaryOperatorType.GreaterOrEqualsThen);
    private static final Set<BoundBinaryOperatorType> INT_ONLY = EnumSet.of(
            BoundBinaryOperatorType.BitwiseAnd, BoundBinaryOperatorType.BitwiseOr, BoundBinaryOperatorType.BitwiseXor,
            BoundBinaryOperatorType.LeftShift, BoundBinaryOperatorType.RightShift);
    private static final Set<BoundBinaryOperatorType> BOOLEAN = EnumSet.of(
            BoundBinaryOperatorType.BitwiseAnd, BoundBinaryOperatorType.BitwiseOr, BoundBinaryOperatorType.BitwiseXor,
            BoundBinaryOperatorType.LogicalAnd, BoundBinaryOperatorType.LogicalOr,
            BoundBinaryOperatorType.Equals, BoundBinaryOperatorType.NotEquals);

    /** What to call a node kind outside the subset; the default is the kind's own name. */
    private static final Map<BoundNodeType, String> CONSTRUCTS = Map.ofEntries(
            Map.entry(BoundNodeType.ArrayLiteralExpression, "arrays are"),
            Map.entry(BoundNodeType.IndexExpression, "indexing is"),
            Map.entry(BoundNodeType.IndexAssignmentExpression, "indexing is"),
            Map.entry(BoundNodeType.MapLiteralExpression, "maps are"),
            Map.entry(BoundNodeType.SetLiteralExpression, "sets are"),
            Map.entry(BoundNodeType.MemberAccessExpression, "member access is"),
            Map.entry(BoundNodeType.MemberAssignmentExpression, "member assignment is"),
            Map.entry(BoundNodeType.StructLiteralExpression, "structs are"),
            Map.entry(BoundNodeType.UnionLiteralExpression, "sum types are"),
            Map.entry(BoundNodeType.TryCatchStatement, "try/catch is"),
            Map.entry(BoundNodeType.TryExpression, "try expressions are"),
            Map.entry(BoundNodeType.ThrowStatement, "throw is"),
            Map.entry(BoundNodeType.JavaMethodCallExpression, "Java interop is"),
            Map.entry(BoundNodeType.JavaStaticFieldExpression, "Java interop is"),
            Map.entry(BoundNodeType.CastExpression, "casts are"),
            Map.entry(BoundNodeType.LambdaExpression, "lambdas are"),
            Map.entry(BoundNodeType.ClosureCallExpression, "closure calls are"),
            Map.entry(BoundNodeType.ScopeExpression, "scope blocks are"),
            Map.entry(BoundNodeType.SpawnExpression, "spawn is"),
            Map.entry(BoundNodeType.SendStatement, "actor messages are"),
            Map.entry(BoundNodeType.MatchExpression, "match and if-expressions are"),
            Map.entry(BoundNodeType.InterfaceCallExpression, "interface calls are"));

    private final LoweredProgram _program;
    private final List<SourceDiagnostic> _diagnostics = new ArrayList<>();
    private int _offset;

    private SubsetValidator(LoweredProgram program) {
        _program = program;
    }

    /** @return One diagnostic per unsupported construct; empty when the program is in the subset. */
    public static List<SourceDiagnostic> validate(LoweredProgram program) {
        SubsetValidator validator = new SubsetValidator(program);
        validator.run();
        return validator._diagnostics;
    }

    private void run() {
        _offset = -1;
        validateBlock(_program.globals());
        for (var entry : _program.functions().entrySet()) {
            FunctionSymbol function = entry.getKey();
            _offset = _program.declarationOffsets().getOrDefault(function, -1);
            validateSignature(function);
            validateBlock(entry.getValue());
        }
    }

    private void validateSignature(FunctionSymbol function) {
        if (!function.getTypeParameters().isEmpty()) report("generic functions are");
        if (!function.getCapturedLocals().isEmpty()) report("functions capturing local variables are");
        for (ParameterSymbol parameter : function.getParameters()) {
            if (!VALUE_TYPES.contains(parameter.getType())) {
                report("parameters of type " + typeName(parameter.getType()) + " are");
            }
        }
        Class<?> returnType = function.getReturnType();
        if (returnType != null && !VALUE_TYPES.contains(returnType)) {
            report("return values of type " + typeName(returnType) + " are");
        }
        boolean entryName = function.getModuleName() == null
                && (function.getName().equals("main") || function.getName().equals("init"));
        if (entryName && !function.getParameters().isEmpty()) report("entry points with parameters are");
        if (entryName && returnType != null) report("entry points returning a value are");
    }

    private void validateBlock(BoundBlockStatement block) {
        for (BoundStatement statement : block.getStatements()) {
            validateStatement(statement);
        }
    }

    private void validateStatement(BoundStatement statement) {
        if (statement.getSourceOffset() >= 0) _offset = statement.getSourceOffset();
        switch (statement) {
            case BoundBlockStatement block -> validateBlock(block);
            case BoundVariableDeclaration declaration -> {
                if (validatesCleanly(declaration.getInitializer())) {
                    requireValueType(declaration.getVariable().getType(), "variables");
                }
            }
            case BoundExpressionStatement expression -> validateExpression(expression.getExpression());
            case BoundLabelStatement ignored -> { }
            case BoundGotoStatement ignored -> { }
            case BoundConditionalGotoStatement conditional -> validateExpression(conditional.getCondition());
            case BoundReturnStatement ret -> {
                if (ret.getExpression() != null) validateExpression(ret.getExpression());
            }
            default -> reportNode(statement);
        }
    }

    private void validateExpression(BoundExpression expression) {
        int saved = _offset;
        if (expression.getSourceOffset() >= 0) _offset = expression.getSourceOffset();
        switch (expression) {
            case BoundLiteralExpression literal -> validateLiteral(literal);
            case BoundVariableExpression variable -> requireValueType(variable.getVariable().getType(), "variables");
            case BoundAssignmentExpression assignment -> {
                if (validatesCleanly(assignment.getExpression())) {
                    requireValueType(assignment.getVariable().getType(), "variables");
                }
            }
            case BoundUnaryExpression unary -> validateUnary(unary);
            case BoundBinaryExpression binary -> validateBinary(binary);
            case BoundConversionExpression conversion -> {
                Class<?> from = conversion.getExpression().getClassType();
                if (from == Integer.class && conversion.getClassType() == Long.class) {
                    validateExpression(conversion.getExpression());
                } else {
                    report("conversions from " + typeName(from) + " to " + typeName(conversion.getClassType()) + " are");
                }
            }
            case BoundCallExpression call -> validateCall(call);
            default -> reportNode(expression);
        }
        _offset = saved;
    }

    private void validateLiteral(BoundLiteralExpression literal) {
        Object value = literal.getValue();
        if (value == null) {
            report("null literals are");
        } else if (!requireValueType(value.getClass(), "literals")) {
            return;
        } else if (value instanceof String text && hasUnpairedSurrogate(text)) {
            report("strings with unpaired surrogates are");
        }
    }

    private void validateUnary(BoundUnaryExpression unary) {
        BoundUnaryOperatorType op = unary.getOperator().getType();
        Class<?> operand = unary.getOperand().getClassType();
        boolean supported = operand == Integer.class
                ? op == BoundUnaryOperatorType.Identity || op == BoundUnaryOperatorType.Negation
                        || op == BoundUnaryOperatorType.OnesComplement
                : operand == Boolean.class && op == BoundUnaryOperatorType.LogicalNegation;
        if (validatesCleanly(unary.getOperand()) && !supported && requireValueType(operand, "values")) {
            report("unary operator " + op + " on " + typeName(operand) + " is");
        }
    }

    private void validateBinary(BoundBinaryExpression binary) {
        BoundBinaryOperatorType op = binary.getOperator().getType();
        Class<?> left = binary.getLeft().getClassType();
        Class<?> right = binary.getRight().getClassType();
        boolean supported;
        if (left == String.class || right == String.class) {
            if (op == BoundBinaryOperatorType.Addition) {
                report("string concatenation is");
                return;
            }
            if (COMPARISON.contains(op) && left == right
                    && op != BoundBinaryOperatorType.Equals && op != BoundBinaryOperatorType.NotEquals) {
                report("string ordering comparisons are");
                return;
            }
            supported = left == right && (op == BoundBinaryOperatorType.Equals || op == BoundBinaryOperatorType.NotEquals);
        } else if (isInteger(left) && isInteger(right)) {
            supported = ARITHMETIC.contains(op) || COMPARISON.contains(op)
                    || (INT_ONLY.contains(op) && left == Integer.class && right == Integer.class);
        } else if (left == Boolean.class && right == Boolean.class) {
            supported = BOOLEAN.contains(op);
        } else {
            supported = false;
        }
        boolean operandsClean = validatesCleanly(binary.getLeft()) & validatesCleanly(binary.getRight());
        if (operandsClean && !supported
                && requireValueType(left, "values") && requireValueType(right, "values")) {
            report("operator " + op + " on " + typeName(left) + " and " + typeName(right) + " is");
        }
    }

    private void validateCall(BoundCallExpression call) {
        FunctionSymbol function = call.getFunction();
        boolean builtinPrint = function == BuiltinFunctions.PRINT || function == BuiltinFunctions.PRINTLN;
        if (!builtinPrint && !_program.functions().containsKey(function)) {
            report("the builtin " + function.getName() + "() is");
            return;
        }
        for (BoundExpression argument : call.getArguments()) {
            validateExpression(argument);
        }
    }

    /**
     * Validates {@code expression}; true when it raised nothing. A construct
     * is reported where it is written, rather than as the type of whatever
     * holds its value.
     */
    private boolean validatesCleanly(BoundExpression expression) {
        int before = _diagnostics.size();
        validateExpression(expression);
        return _diagnostics.size() == before;
    }

    private static boolean isInteger(Class<?> type) {
        return type == Integer.class || type == Long.class;
    }

    /** Reports {@code type} unless it is a value type; returns whether it was. */
    private boolean requireValueType(Class<?> type, String what) {
        if (VALUE_TYPES.contains(type)) return true;
        if (type == Double.class) {
            report("float values are");
        } else {
            report(what + " of type " + typeName(type) + " are");
        }
        return false;
    }

    private void reportNode(BoundNode node) {
        report(CONSTRUCTS.getOrDefault(node.getType(), node.getType() + " is"));
    }

    private void report(String constructIsOrAre) {
        _diagnostics.add(SourceDiagnostic.unsupported(_program.file(), _program.text(), _offset, constructIsOrAre));
    }

    static String typeName(Class<?> type) {
        if (type == null) return "void";
        if (type == Integer.class) return "int";
        if (type == Long.class) return "long";
        if (type == Boolean.class) return "bool";
        if (type == String.class) return "string";
        if (type == Double.class) return "float";
        if (type == Object.class) return "any";
        String name = type.getSimpleName();
        return name.startsWith("Siyo") ? name.substring(4).toLowerCase() : name;
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                } else {
                    return true;
                }
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
