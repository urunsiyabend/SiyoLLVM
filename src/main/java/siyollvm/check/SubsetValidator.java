package siyollvm.check;

import codeanalysis.BuiltinFunctions;
import codeanalysis.FunctionSymbol;
import codeanalysis.VariableSymbol;
import codeanalysis.ParameterSymbol;
import codeanalysis.SiyoArray;
import codeanalysis.SiyoMap;
import codeanalysis.SiyoStruct;
import codeanalysis.binding.BoundArrayLiteralExpression;
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
import codeanalysis.binding.BoundIndexAssignmentExpression;
import codeanalysis.binding.BoundIndexExpression;
import codeanalysis.binding.BoundJavaMethodCallExpression;
import codeanalysis.binding.BoundLabelStatement;
import codeanalysis.binding.BoundLiteralExpression;
import codeanalysis.binding.BoundMapLiteralExpression;
import codeanalysis.binding.BoundMemberAccessExpression;
import codeanalysis.binding.BoundMemberAssignmentExpression;
import codeanalysis.binding.BoundNode;
import codeanalysis.binding.BoundNodeType;
import codeanalysis.binding.BoundReturnStatement;
import codeanalysis.binding.BoundStatement;
import codeanalysis.binding.BoundStructLiteralExpression;
import codeanalysis.binding.BoundThrowStatement;
import codeanalysis.binding.BoundTryCatchStatement;
import codeanalysis.binding.BoundTryExpression;
import codeanalysis.binding.BoundUnaryExpression;
import codeanalysis.binding.BoundUnaryOperatorType;
import codeanalysis.binding.BoundVariableDeclaration;
import codeanalysis.binding.BoundVariableExpression;
import siyollvm.frontend.LoweredProgram;
import siyollvm.ir.Intrinsics;
import siyollvm.frontend.SourceDiagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
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
 *
 * <p>{@code any} ({@code Object}) values are accepted where the JVM backend
 * gives them a meaning SiyoLLVM reproduces: stored, passed, returned, printed,
 * joined to a string, compared with {@code ==}/{@code !=} (Objects.equals),
 * and narrowed back to a declared type with a checked cast. The binder also
 * lets an any reach arithmetic, ordering, logic, conditions and the string
 * builtins, where the JVM backend unboxes it to the other operand's type; those
 * are rejected.
 *
 * <p>{@code try}/{@code catch} statements, {@code throw} and {@code error()}
 * are accepted with values of the types above. The lowerer leaves a try
 * statement structured, so both of its bodies (with their nested blocks,
 * labels and gotos) are checked like any other statements.
 *
 * <p>A {@code try} expression is accepted where the JVM backend evaluates it
 * on an empty operand stack: as a statement, a declaration's or assignment's
 * value, a condition, a returned or thrown value, or the first argument of a
 * user function or {@code toString} (recursively, so {@code imut s =
 * f(g(try …))} qualifies). Its exception handler clears the operand stack, so
 * where the stack is not empty (a right arithmetic or comparison operand, an
 * argument of {@code print}, a later argument) the reference fails
 * verification. A left operand, the right operand of {@code &&}/{@code ||}, a
 * string builtin's first argument and {@code error()}'s argument work on the
 * reference but are rejected here for now, conservatively. Its bodies end in
 * a value, in no expression at all (the value is null), or in {@code error()}.
 *
 * <p>Arrays ({@code SiyoArray}) are accepted as values of every kind above
 * (variables, parameters, returns, elements, any values, thrown values, catch
 * variables), with literals, indexing, index assignment, {@code len},
 * {@code push}, {@code pop} and {@code removeAt}. The binder also lets an
 * {@code any} be indexed or passed as the array of those builtins; the JVM
 * backend reads it through a checkcast to List, which is accepted, but writes
 * to it with invokeinterface, which fails with IncompatibleClassChangeError
 * for a non-list, so those are rejected. A try expression is never an
 * element, an index or a pushed value: the operand stack is not empty there.
 * Strings are indexed too ({@code charAt}), and {@code range} and
 * {@code split} make arrays.
 *
 * <p>{@code for … in} reaches the validator as the binder's desugaring: a
 * block declaring {@code _colN}, then a loop (lowered to labels and gotos) on
 * {@code _idxN < len(_colN)} whose body reads {@code _colN[_idxN]}. Over an
 * array or a string that is only len and indexing, so it needs nothing of its
 * own; over a map the binder wraps the map in {@code $mapKeys}, a fresh key
 * array, which is accepted; over a set or an any it wraps the collection in
 * a helper call, which is rejected.
 *
 * <p>Structs ({@code SiyoStruct}) are values of every kind above too. A
 * struct declaration binds to a placeholder literal statement; literals,
 * field reads ({@code p.x}) and field writes ({@code p.x = v}, and
 * {@code p.x += v}, which the parser desugars into a write of a read) are
 * accepted with fields of the value types, as is {@code typeName}. The binder
 * gives a field read the field's declared type when it can tell which struct
 * the target is, else {@code any}.
 *
 * <p>An impl method is a user function like any other (named
 * {@code Type.method}, with {@code self} as its first parameter), so its
 * signature and body are checked as above and a call to it is a user call.
 * An enum member binds to an int literal and needs nothing of its own.
 *
 * <p>Maps ({@code SiyoMap}) are values of every kind above too, with literals
 * ({@code {k: v}}, keys and values of any value type), {@code map()},
 * indexing and index assignment by key, {@code len}, {@code for k in m},
 * printing and {@code toString}. Their methods bind as Java method calls on
 * {@code SiyoMap}; {@code get}, {@code set}, {@code has}, {@code remove},
 * {@code size} and {@code keys} are accepted when the binder resolved them to
 * the pinned class's instance methods of those signatures, and every other
 * method is rejected by name. Maps compare by identity, so {@code ==} between
 * two maps is a bind error and between a map and an any is Objects.equals.
 */
public final class SubsetValidator {

    private static final Set<Class<?>> VALUE_TYPES =
            Set.of(Integer.class, Long.class, Boolean.class, String.class, Object.class, SiyoArray.class,
                    SiyoStruct.class, SiyoMap.class);

    /**
     * Types an any holds boxed. The JVM backend boxes them when declaring an
     * any or passing one, but not when assigning or returning one.
     */
    private static final Set<Class<?>> PRIMITIVES = Set.of(Integer.class, Long.class, Boolean.class);

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

    /**
     * Builtins over strings and numbers whose parameters all have value types
     * (range and split return arrays). Compared by identity:
     * FunctionSymbol.equals looks at name and arity only, so the overloads of
     * toInt, and a user function named like a builtin, would otherwise collide.
     */
    private static final Set<FunctionSymbol> STRING_BUILTINS = Collections.newSetFromMap(new IdentityHashMap<>());

    static {
        STRING_BUILTINS.addAll(List.of(
                BuiltinFunctions.SUBSTRING, BuiltinFunctions.CONTAINS, BuiltinFunctions.INDEX_OF,
                BuiltinFunctions.STARTS_WITH, BuiltinFunctions.ENDS_WITH, BuiltinFunctions.REPLACE,
                BuiltinFunctions.TRIM, BuiltinFunctions.TO_UPPER, BuiltinFunctions.TO_LOWER,
                BuiltinFunctions.CHR, BuiltinFunctions.ORD, BuiltinFunctions.PARSE_INT, BuiltinFunctions.PARSE_LONG,
                BuiltinFunctions.TO_LONG, BuiltinFunctions.TO_INT_LONG, BuiltinFunctions.TO_INT_STR,
                BuiltinFunctions.RANGE, BuiltinFunctions.SPLIT));
    }

    /** Array builtins taking the array first; len is checked on its own. */
    private static final Set<FunctionSymbol> ARRAY_BUILTINS = Collections.newSetFromMap(new IdentityHashMap<>());

    static {
        ARRAY_BUILTINS.addAll(List.of(BuiltinFunctions.PUSH, BuiltinFunctions.POP, BuiltinFunctions.REMOVE_AT));
    }

    /** What the binder's for-in desugaring calls over values that are neither arrays nor maps. */
    private static final Map<FunctionSymbol, String> FOR_IN_HELPERS = new IdentityHashMap<>(Map.of(
            BuiltinFunctions.ITERABLE, "for … in over any values is",
            BuiltinFunctions.SET_VALUES, "for … in over sets is"));

    /**
     * The SiyoMap methods in the subset: name, then the parameter count and
     * return descriptor of the pinned {@code codeanalysis.SiyoMap}'s instance
     * method (get, set, has and remove also have a String-keyed overload that
     * behaves alike).
     */
    private static final Map<String, Map.Entry<Integer, String>> MAP_METHODS = Map.of(
            "get", Map.entry(1, "Ljava/lang/Object;"),
            "set", Map.entry(2, "V"),
            "has", Map.entry(1, "Z"),
            "remove", Map.entry(1, "V"),
            "size", Map.entry(0, "I"),
            "keys", Map.entry(0, "Lcodeanalysis/SiyoArray;"));

    /** What to call a node kind outside the subset; the default is the kind's own name. */
    private static final Map<BoundNodeType, String> CONSTRUCTS = Map.ofEntries(
            Map.entry(BoundNodeType.SetLiteralExpression, "sets are"),
            Map.entry(BoundNodeType.UnionLiteralExpression, "sum types are"),
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
    /** The file being validated. */
    private LoweredProgram.Unit _unit;
    private int _offset;
    /** The function being validated; null for the global initialiser. */
    private FunctionSymbol _function;
    /**
     * Whether the expression validated next is evaluated, by the JVM backend,
     * on an empty operand stack; {@link #validateExpression} clears it.
     */
    private boolean _emptyStack;

    private SubsetValidator(LoweredProgram program) {
        _program = program;
    }

    /** @return One diagnostic per unsupported construct; empty when the program is in the subset. */
    public static List<SourceDiagnostic> validate(LoweredProgram program) {
        SubsetValidator validator = new SubsetValidator(program);
        validator.run();
        return validator._diagnostics;
    }

    /** Every file in turn, each reported against itself: its top level, then its functions. */
    private void run() {
        for (LoweredProgram.Unit unit : _program.units()) {
            _unit = unit;
            _offset = -1;
            _function = null;
            validateBlock(unit.topLevel());
            for (var entry : unit.functions().entrySet()) {
                FunctionSymbol function = entry.getKey();
                _function = function;
                _offset = unit.declarationOffsets().getOrDefault(function, -1);
                validateSignature(function);
                validateBlock(entry.getValue());
            }
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
        // The main file's main and init, and a module's own init, are run by the entry point.
        boolean mainFile = _unit == _program.main();
        boolean entryName = function.getModuleName() == null && (mainFile
                ? function.getName().equals("main") || function.getName().equals("init")
                : function == _unit.init());
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
                _emptyStack = true;
                if (validatesCleanly(declaration.getInitializer())) {
                    requireValueType(declaration.getVariable().getType(), "variables");
                }
            }
            case BoundExpressionStatement expression -> {
                _emptyStack = true;
                validateExpression(expression.getExpression());
            }
            case BoundLabelStatement ignored -> { }
            case BoundGotoStatement ignored -> { }
            case BoundConditionalGotoStatement conditional -> {
                // The binder admits an any as a condition; the JVM backend unboxes it.
                Class<?> type = conditional.getCondition().getClassType();
                _emptyStack = true;
                if (validatesCleanly(conditional.getCondition()) && type != Boolean.class) {
                    report("conditions of type " + typeName(type) + " are");
                }
            }
            case BoundReturnStatement ret -> {
                if (ret.getExpression() != null) _emptyStack = true;
                if (ret.getExpression() != null && validatesCleanly(ret.getExpression())) {
                    // The JVM backend returns the primitive unboxed, which fails verification.
                    Class<?> type = ret.getExpression().getClassType();
                    if (_function != null && _function.getReturnType() == Object.class && PRIMITIVES.contains(type)) {
                        report("returning " + typeName(type) + " from a function returning any is");
                    }
                }
            }
            case BoundTryCatchStatement tryCatch -> {
                validateStatement(tryCatch.getTryBody());
                requireValueType(tryCatch.getErrorVariable().getType(), "catch variables");
                validateStatement(tryCatch.getCatchBody());
            }
            case BoundThrowStatement raise -> {
                _emptyStack = true;
                if (validatesCleanly(raise.getExpression())) {
                    requireValueType(raise.getExpression().getClassType(), "values");
                }
            }
            default -> reportNode(statement);
        }
    }

    private void validateExpression(BoundExpression expression) {
        int saved = _offset;
        if (expression.getSourceOffset() >= 0) _offset = expression.getSourceOffset();
        boolean emptyStack = _emptyStack;
        _emptyStack = false;
        switch (expression) {
            case BoundLiteralExpression literal -> validateLiteral(literal);
            case BoundVariableExpression variable -> {
                if (resolves(variable.getVariable())) requireValueType(variable.getVariable().getType(), "variables");
            }
            case BoundAssignmentExpression assignment -> {
                _emptyStack = emptyStack;
                if (!resolves(assignment.getVariable())) break;
                if (validatesCleanly(assignment.getExpression())
                        && requireValueType(assignment.getVariable().getType(), "variables")) {
                    // The JVM backend stores the primitive unboxed, which fails verification.
                    Class<?> type = assignment.getExpression().getClassType();
                    if (assignment.getVariable().getType() == Object.class && PRIMITIVES.contains(type)) {
                        report("assignments of " + typeName(type) + " to any are");
                    }
                }
            }
            case BoundUnaryExpression unary -> validateUnary(unary);
            case BoundBinaryExpression binary -> validateBinary(binary);
            case BoundConversionExpression conversion -> {
                Class<?> from = conversion.getExpression().getClassType();
                if (from == Integer.class && conversion.getClassType() == Long.class) {
                    _emptyStack = emptyStack;
                    validateExpression(conversion.getExpression());
                } else {
                    report("conversions from " + typeName(from) + " to " + typeName(conversion.getClassType()) + " are");
                }
            }
            case BoundCallExpression call -> validateCall(call, emptyStack);
            case BoundTryExpression tryExpression -> validateTry(tryExpression, emptyStack);
            case BoundArrayLiteralExpression array -> {
                for (BoundExpression element : array.getElements()) {
                    if (validatesCleanly(element)) requireValueType(element.getClassType(), "array elements");
                }
            }
            case BoundMapLiteralExpression literal -> {
                for (BoundExpression key : literal.getKeys()) {
                    if (validatesCleanly(key)) requireValueType(key.getClassType(), "map keys");
                }
                for (BoundExpression value : literal.getValues()) {
                    if (validatesCleanly(value)) requireValueType(value.getClassType(), "map values");
                }
            }
            case BoundJavaMethodCallExpression call when call.getTarget() != null
                    && call.getTarget().getClassType() == SiyoMap.class -> validateMapMethod(call);
            case BoundIndexExpression index -> validateIndex(index);
            case BoundIndexAssignmentExpression assignment -> validateIndexAssignment(assignment);
            case BoundStructLiteralExpression literal -> {
                for (BoundExpression value : literal.getFieldValues().values()) {
                    if (validatesCleanly(value)) requireValueType(value.getClassType(), "struct fields");
                }
            }
            case BoundMemberAccessExpression access -> {
                if (validatesCleanly(access.getTarget()) && requireStruct(access.getTarget())) {
                    requireValueType(access.getClassType(), "struct fields");
                }
            }
            case BoundMemberAssignmentExpression assignment -> {
                boolean clean = validatesCleanly(assignment.getTarget()) & validatesCleanly(assignment.getValue());
                if (clean && requireStruct(assignment.getTarget())) {
                    requireValueType(assignment.getValue().getClassType(), "struct fields");
                }
            }
            default -> reportNode(expression);
        }
        // Set for a first argument that was not there (a call with none).
        _emptyStack = false;
        _offset = saved;
    }

    /** The binder makes member access on anything but a struct an error; this keeps the emitter safe. */
    private boolean requireStruct(BoundExpression target) {
        if (target.getClassType() == SiyoStruct.class) return true;
        report("member access on values of type " + typeName(target.getClassType()) + " is");
        return false;
    }

    /** A SiyoMap method call: one of {@link #MAP_METHODS}, resolved to the pinned signature. */
    private void validateMapMethod(BoundJavaMethodCallExpression call) {
        String name = call.getMethodName();
        Map.Entry<Integer, String> expected = MAP_METHODS.get(name);
        var signature = call.getResolvedSignature();
        if (expected == null || signature == null || signature.isStatic() || signature.isConstructor()
                || !"codeanalysis/SiyoMap".equals(signature.getOwnerInternalName())
                || signature.getParamCount() != expected.getKey() || call.getArguments().size() != expected.getKey()
                || !expected.getValue().equals(signature.getReturnDescriptor())) {
            report("map method " + name + "() is");
            return;
        }
        if (!validatesCleanly(call.getTarget())) return;
        for (BoundExpression argument : call.getArguments()) {
            if (validatesCleanly(argument)) requireValueType(argument.getClassType(), "values");
        }
    }

    /**
     * An array element read, a map read by key, or a string's charAt; an any
     * target is checkcast to an array first.
     */
    private void validateIndex(BoundIndexExpression index) {
        Class<?> target = index.getTarget().getClassType();
        boolean clean = validatesCleanly(index.getTarget()) & validatesCleanly(index.getIndex());
        if (!clean) return;
        if (target == SiyoMap.class) {
            if (requireValueType(index.getIndex().getClassType(), "map keys")) {
                requireValueType(index.getClassType(), "values");
            }
        } else if (target != SiyoArray.class && target != Object.class && target != String.class) {
            report("indexing values of type " + typeName(target) + " is");
        } else {
            requireValueType(index.getClassType(), "values");
        }
    }

    private void validateIndexAssignment(BoundIndexAssignmentExpression assignment) {
        Class<?> target = assignment.getTarget().getClassType();
        boolean clean = validatesCleanly(assignment.getTarget()) & validatesCleanly(assignment.getIndex())
                & validatesCleanly(assignment.getValue());
        if (!clean) return;
        if (target == Object.class) {
            report("index assignment on any values is");
        } else if (target == SiyoMap.class) {
            if (requireValueType(assignment.getIndex().getClassType(), "map keys")) {
                requireValueType(assignment.getValue().getClassType(), "values");
            }
        } else if (target != SiyoArray.class) {
            report("index assignment on values of type " + typeName(target) + " is");
        } else {
            requireValueType(assignment.getValue().getClassType(), "values");
        }
    }

    private void validateLiteral(BoundLiteralExpression literal) {
        Object value = literal.getValue();
        if (value == null) {
            // null is an any; its uses are checked where it is used.
            return;
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
        if ((left == String.class || right == String.class) && op == BoundBinaryOperatorType.Addition) {
            // Concatenation joins a string with any value type, either side.
            boolean operandsClean = validatesCleanly(binary.getLeft()) & validatesCleanly(binary.getRight());
            if (operandsClean && requireValueType(left, "values")) requireValueType(right, "values");
            return;
        } else if (left == Object.class || right == Object.class) {
            // Objects.equals; anything else would unbox to the other side's type.
            supported = op == BoundBinaryOperatorType.Equals || op == BoundBinaryOperatorType.NotEquals;
        } else if (left == String.class || right == String.class) {
            // == and != compare values; < <= > >= follow compareTo.
            supported = left == right && COMPARISON.contains(op);
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

    private void validateCall(BoundCallExpression call, boolean emptyStack) {
        FunctionSymbol function = call.getFunction();
        boolean builtinPrint = function == BuiltinFunctions.PRINT || function == BuiltinFunctions.PRINTLN;
        FunctionSymbol target;
        try {
            target = _program.target(function);
        } catch (IllegalStateException e) {
            report("calls to " + function.getName() + ", which names no single module function, are");
            return;
        }
        if (target != null && Intrinsics.isUnimplemented(_program, target)) {
            // A shipped std stub with no native implementation (std/io.walk, strings.builder).
            report(Intrinsics.displayName(_program, target) + "() is");
            return;
        }
        // A user function's or toString's first argument is the first thing the
        // JVM backend pushes; print and println push System.out before theirs.
        boolean firstOnEmptyStack = emptyStack
                && (function == BuiltinFunctions.TO_STRING || target != null);
        if (function == BuiltinFunctions.TO_STRING) {
            _emptyStack = firstOnEmptyStack;
            for (BoundExpression argument : call.getArguments()) {
                if (validatesCleanly(argument)) requireValueType(argument.getClassType(), "values");
            }
            return;
        }
        if (function == BuiltinFunctions.ERROR) {
            // Throws its argument as it is, as throw does.
            for (BoundExpression argument : call.getArguments()) {
                if (validatesCleanly(argument)) requireValueType(argument.getClassType(), "values");
            }
            return;
        }
        if (function == BuiltinFunctions.LEN) {
            // len(x) takes any value; strings, arrays and maps are in the subset.
            BoundExpression argument = call.getArguments().get(0);
            Class<?> type = argument.getClassType();
            if (validatesCleanly(argument) && requireValueType(type, "values")
                    && type != String.class && type != SiyoArray.class && type != SiyoMap.class) {
                report("len() on " + typeName(type) + " is");
            }
            return;
        }
        if (ARRAY_BUILTINS.contains(function)) {
            boolean clean = true;
            for (BoundExpression argument : call.getArguments()) {
                clean &= validatesCleanly(argument) && requireValueType(argument.getClassType(), "values");
            }
            if (!clean) return;
            Class<?> array = call.getArguments().get(0).getClassType();
            if (array != SiyoArray.class) {
                report(function.getName() + "() on " + typeName(array) + " values is");
            } else if (function == BuiltinFunctions.POP) {
                // The binder types pop as the array's tracked element type.
                requireValueType(call.getClassType(), "values");
            }
            return;
        }
        if (function == BuiltinFunctions.TYPE_NAME) {
            for (BoundExpression argument : call.getArguments()) {
                if (validatesCleanly(argument)) requireValueType(argument.getClassType(), "values");
            }
            return;
        }
        if (function == BuiltinFunctions.NEW_MAP) {
            return;
        }
        if (function == BuiltinFunctions.MAP_KEYS) {
            // for k in m: the binder only wraps a map.
            BoundExpression argument = call.getArguments().get(0);
            if (validatesCleanly(argument) && argument.getClassType() != SiyoMap.class) {
                report("for … in over values of type " + typeName(argument.getClassType()) + " is");
            }
            return;
        }
        if (FOR_IN_HELPERS.containsKey(function)) {
            report(FOR_IN_HELPERS.get(function));
            return;
        }
        if (!builtinPrint && !STRING_BUILTINS.contains(function) && target == null) {
            report("the builtin " + function.getName() + "() is");
            return;
        }
        _emptyStack = firstOnEmptyStack;
        for (BoundExpression argument : call.getArguments()) {
            // A builtin would cast an any argument; only user functions take one.
            if (validatesCleanly(argument) && STRING_BUILTINS.contains(function)
                    && argument.getClassType() == Object.class) {
                report("arguments of type any to " + function.getName() + "() are");
            }
        }
    }

    /**
     * A try expression: its position (see the class comment), its bodies as
     * statements, and the value each body ends in. The JVM backend boxes that
     * value and narrows it to the expression's type after the join; a body
     * ending in a call with no value (other than error(), which never returns)
     * leaves it nothing to push.
     */
    private void validateTry(BoundTryExpression tryExpression, boolean emptyStack) {
        if (!emptyStack) {
            report("try expressions here (only as a statement, a declaration's or assignment's value, a condition,"
                    + " a returned or thrown value, or the first argument of a function or toString) are");
        }
        int before = _diagnostics.size();
        validateStatement(tryExpression.getTryBody());
        requireValueType(tryExpression.getErrorVariable().getType(), "catch variables");
        validateStatement(tryExpression.getCatchBody());
        for (BoundStatement body : List.of(tryExpression.getTryBody(), tryExpression.getCatchBody())) {
            if (body instanceof BoundBlockStatement block && !block.getStatements().isEmpty()
                    && block.getStatements().getLast() instanceof BoundExpressionStatement tail
                    && tail.getExpression().getClassType() == null
                    && !(tail.getExpression() instanceof BoundCallExpression call
                            && call.getFunction() == BuiltinFunctions.ERROR)) {
                report("try expression bodies ending in a call with no value are");
            }
        }
        if (_diagnostics.size() == before) requireValueType(tryExpression.getClassType(), "try expressions");
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
        if (node instanceof BoundJavaMethodCallExpression call && call.getTarget() != null
                && (call.getTarget().getClassType() == SiyoStruct.class
                || call.getTarget().getClassType() == Object.class)) {
            report("method calls on a struct whose type the binder cannot tell are");
            return;
        }
        if (node.getType() == BoundNodeType.JavaMethodCallExpression) {
            report("Java interop is");
            return;
        }
        report(CONSTRUCTS.getOrDefault(node.getType(), node.getType() + " is"));
    }

    /** Whether a module variable names a known module global; reports it when not. */
    private boolean resolves(VariableSymbol variable) {
        try {
            _program.target(variable);
            return true;
        } catch (IllegalStateException e) {
            report(variable.getName() + ", which names no module global, is");
            return false;
        }
    }

    private void report(String constructIsOrAre) {
        _diagnostics.add(SourceDiagnostic.unsupported(_unit.file(), _unit.text(), _offset, constructIsOrAre));
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
