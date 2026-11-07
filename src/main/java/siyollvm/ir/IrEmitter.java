package siyollvm.ir;

import codeanalysis.BuiltinFunctions;
import codeanalysis.FunctionSymbol;
import codeanalysis.LabelSymbol;
import codeanalysis.ParameterSymbol;
import codeanalysis.SiyoArray;
import codeanalysis.SiyoMap;
import codeanalysis.SiyoStruct;
import codeanalysis.VariableSymbol;
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
import codeanalysis.binding.BoundReturnStatement;
import codeanalysis.binding.BoundStatement;
import codeanalysis.binding.BoundStructLiteralExpression;
import codeanalysis.binding.BoundThrowStatement;
import codeanalysis.binding.BoundTryCatchStatement;
import codeanalysis.binding.BoundTryExpression;
import codeanalysis.binding.BoundUnaryExpression;
import codeanalysis.binding.BoundVariableDeclaration;
import codeanalysis.binding.BoundVariableExpression;
import siyollvm.frontend.LoweredProgram;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits textual LLVM IR (opaque pointers) for a program {@link
 * siyollvm.check.SubsetValidator} accepted.
 *
 * <p>Every LLVM name is index-based ({@code @siyo.fn.N}, {@code %pN},
 * {@code %vN}, {@code %tN}), so no source identifier reaches the IR. Locals
 * live in {@code alloca} slots; clang promotes them. Each label of the
 * lowered tree starts a basic block, and code after a terminator goes into
 * a fresh block with no predecessors, so unreachable code stays well formed.
 *
 * <p>Semantics follow the JVM backend: integer arithmetic wraps, division
 * and remainder check for zero (and for {@code MIN / -1}, which LLVM leaves
 * undefined), shift counts are masked, and falling off the end of a
 * value-returning function returns the type's default.
 *
 * <p>A value of static type {@code any} ({@code Object}) is a {@code ptr} to
 * a tagged heap object or null: a string as it is, an int, long or bool boxed
 * by the runtime. Where the binder lets an {@code any} meet a declared type
 * (declaration, assignment, argument, return) the value is boxed, or unboxed
 * and cast with the runtime's check, as the JVM backend's checkcast does.
 *
 * <p>An array is a {@code ptr} to the runtime's {@code SiyoArray}, whose items
 * are any values, as the JVM backend's arrays are lists of objects: an
 * element is boxed going in, and narrowed coming out to the type the binder
 * gives the read (an element type it tracks, else {@code any}) with the same
 * checked cast. Index errors and a null array raise in the runtime. A
 * string's {@code s[i]} is {@code charAt}, a one-unit string. {@code for … in}
 * arrives desugared into len and indexing, so it has no code of its own.
 *
 * <p>A struct is a {@code ptr} to the runtime's {@code SiyoStruct}, a field
 * map like the JVM backend's {@code SiyoObject}: a literal makes one with its
 * field names (a constant array of interned strings, {@code @siyo.fields.N})
 * in the literal's order and stores each value boxed. The bound member nodes
 * do not say which struct a target is, so a field is read and written by
 * name in the runtime, which raises NullPointerException for a null struct;
 * a read is narrowed to the type the binder gives it with the checked cast of
 * an any read. A struct declaration leaves only a placeholder statement.
 *
 * <p>A map is a {@code ptr} to the runtime's {@code SiyoMap}, an
 * insertion-ordered table of any keys and values like the JVM's
 * {@code SiyoMap}: keys and values are boxed going in, and a read
 * ({@code m[k]}, {@code m.get(k)}) is narrowed like an element read, except
 * that unboxing a missing key's null into an int or bool raises the
 * NullPointerException naming {@code SiyoMap.get}, as the JVM's does. The
 * methods the validator accepts ({@code get}, {@code set}, {@code has},
 * {@code remove}, {@code size}, {@code keys}) arrive as Java method calls on
 * {@code SiyoMap} and are each one runtime call; {@code for k in m} arrives
 * as the binder's loop over {@code $mapKeys(m)}, a fresh key array.
 *
 * <p>Exceptions never unwind the native stack. A raise (a Siyo {@code throw}
 * or {@code error()}, or a runtime error) records a pending exception in the
 * runtime and returns normally; after every call that can raise (a Siyo
 * function, or a runtime function in {@link RuntimeIr#RAISING}) the code
 * branches on the pending flag to its handler: the innermost enclosing catch
 * block of the function, or else a block returning the type's default to the
 * caller, which checks in turn. {@code siyo_program} reports an exception
 * still pending after the globals, {@code init()} or {@code main()} as
 * uncaught. A try body is emitted in place, so its jumps (break, continue,
 * return) need no unwinding: the handler is a property of where code is.
 * A {@code try} expression is a try/catch whose bodies each store their last
 * expression's value into one slot, which the join block loads.
 *
 * <p>Nodes outside the subset throw {@link IllegalStateException}: the
 * emitter never guesses.
 */
public final class IrEmitter {
    private final LoweredProgram _program;
    private final StringConstants _strings = new StringConstants();
    /** Every unit's functions, by identity: two modules' {@code helper()} are equal symbols. */
    private final Map<FunctionSymbol, String> _functionNames = new IdentityHashMap<>();
    /** Every unit's globals in order (VariableSymbol compares by identity). */
    private final Map<VariableSymbol, String> _globals = new LinkedHashMap<>();
    /** Definitions of the struct literals' field-name arrays, {@code @siyo.fields.N}. */
    private final List<String> _fieldNames = new java.util.ArrayList<>();

    // Per-function state.
    private FunctionSymbol _function;
    private StringBuilder _allocas;
    private StringBuilder _body;
    private Map<VariableSymbol, String> _slots;
    private Map<LabelSymbol, String> _labels;
    private int _temps;
    private int _blocks;
    private String _block;
    private boolean _terminated;
    /** Catch blocks of the try statements being emitted, innermost first. */
    private Deque<String> _handlers;
    /** Whether the function's default-return handler ({@code unwind}) is used. */
    private boolean _unwindUsed;

    /**
     * An emitted value. {@code nullOrigin}, for an any, names the
     * {@code siyo_rt_unbox_*} variant to narrow it with when the JVM's
     * NullPointerException message names where it came from (a map read),
     * else null.
     */
    private record Value(String ref, Class<?> type, String nullOrigin) {
        Value(String ref, Class<?> type) {
            this(ref, type, null);
        }
    }

    private IrEmitter(LoweredProgram program) {
        _program = program;
    }

    /** @return The module's IR text. */
    public static String emit(LoweredProgram program) {
        return new IrEmitter(program).module();
    }

    private String module() {
        int index = 0;
        for (LoweredProgram.Unit unit : _program.units()) {
            for (FunctionSymbol function : unit.functions().keySet()) {
                _functionNames.put(function, "@siyo.fn." + index++);
            }
            for (BoundStatement statement : unit.topLevel().getStatements()) {
                if (statement instanceof BoundVariableDeclaration declaration) {
                    _globals.put(declaration.getVariable(), "@siyo.g." + _globals.size());
                }
            }
        }

        StringBuilder functions = new StringBuilder();
        for (LoweredProgram.Unit unit : _program.units()) {
            for (var entry : unit.functions().entrySet()) {
                functions.append(function(entry.getKey(), entry.getValue())).append('\n');
            }
        }
        for (int i = 0; i < _program.units().size(); i++) {
            functions.append(globalsInitialiser(_program.units().get(i), initialiserName(i))).append('\n');
        }
        functions.append(entryPoint());

        StringBuilder ir = new StringBuilder();
        ir.append("; Generated by SiyoLLVM (experimental).\n\n");
        ir.append(RuntimeIr.text()).append('\n');
        ir.append(_strings.definitions());
        for (String definition : _fieldNames) ir.append(definition).append('\n');
        ir.append('\n');
        for (var entry : _globals.entrySet()) {
            Class<?> type = entry.getKey().getType();
            ir.append(entry.getValue()).append(" = internal global ").append(LlvmTypes.of(type)).append(' ')
                    .append(LlvmTypes.zero(type)).append('\n');
        }
        ir.append('\n').append(functions);
        return ir.toString();
    }

    // --- functions -----------------------------------------------------------

    private void begin(FunctionSymbol function) {
        _function = function;
        _allocas = new StringBuilder();
        _body = new StringBuilder();
        _slots = new IdentityHashMap<>();
        _labels = new IdentityHashMap<>();
        _temps = 0;
        _blocks = 0;
        _terminated = false;
        _block = "body";
        _handlers = new ArrayDeque<>();
        _unwindUsed = false;
    }

    private String function(FunctionSymbol function, BoundBlockStatement body) {
        begin(function);
        StringBuilder params = new StringBuilder();
        List<ParameterSymbol> parameters = function.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            ParameterSymbol parameter = parameters.get(i);
            String type = LlvmTypes.of(parameter.getType());
            if (i > 0) params.append(", ");
            params.append(type).append(" %p").append(i);
            String slot = slotFor(parameter);
            _allocas.append("  store ").append(type).append(" %p").append(i).append(", ptr ").append(slot).append('\n');
        }

        for (BoundStatement statement : body.getStatements()) {
            statement(statement);
        }
        Class<?> resultType = function.getReturnType();
        String defaultReturn = resultType == null ? "ret void"
                : "ret " + LlvmTypes.of(resultType) + " " + LlvmTypes.zero(resultType);
        if (!_terminated) terminate(defaultReturn);
        if (_unwindUsed) {
            // The value is never read: the caller sees the pending exception first.
            startBlock("unwind");
            terminate(defaultReturn);
        }

        String returnType = function.getReturnType() == null ? "void" : LlvmTypes.of(function.getReturnType());
        return "define internal " + returnType + " " + _functionNames.get(function) + "(" + params + ") {\n"
                + "entry:\n"
                + "  %stack.marker = alloca i8\n"
                + _allocas
                + "  %stack.here = ptrtoint ptr %stack.marker to i64\n"
                + "  %stack.limit = load i64, ptr @siyo_rt_stack_limit\n"
                + "  %stack.low = icmp ult i64 %stack.here, %stack.limit\n"
                + "  br i1 %stack.low, label %stack.overflow, label %body\n"
                + "stack.overflow:\n"
                + "  call void @siyo_rt_stack_overflow()\n"
                + "  unreachable\n"
                + "body:\n"
                + _body
                + "}\n";
    }

    /** The main file's is {@code @siyo.init_globals}; module {@code i}'s {@code @siyo.init_globals.i}. */
    private String initialiserName(int unit) {
        return unit == _program.units().size() - 1 ? "@siyo.init_globals" : "@siyo.init_globals." + unit;
    }

    /** Runs a file's file-level declarations in source order, storing into globals. */
    private String globalsInitialiser(LoweredProgram.Unit unit, String name) {
        begin(null);
        for (BoundStatement statement : unit.topLevel().getStatements()) {
            statement(statement);
        }
        if (!_terminated) terminate("ret void");
        if (_unwindUsed) {
            startBlock("unwind");
            terminate("ret void");
        }
        return "define internal void " + name + "() {\nentry:\n" + _allocas
                + "  br label %body\nbody:\n" + _body + "}\n";
    }

    /**
     * {@code siyo_program}, which the C runtime's {@code main} runs on a thread
     * of known stack size after setting the stack limit: each module's
     * globals then its {@code init()}, in initialisation order, then the main
     * file's globals, {@code init()} and {@code main()} if declared. An
     * exception still pending after any of them is uncaught: the runtime
     * reports it and exits. A module runs in the JVM's class initialiser, so
     * one escaping a module is reported as the JVM reports a failed class
     * initialiser.
     */
    private String entryPoint() {
        StringBuilder program = new StringBuilder("define void @siyo_program() {\nentry:\n");
        List<String> calls = new java.util.ArrayList<>();
        List<Boolean> inModule = new java.util.ArrayList<>();
        List<LoweredProgram.Unit> units = _program.units();
        for (int i = 0; i < units.size() - 1; i++) {
            calls.add(initialiserName(i));
            inModule.add(true);
            FunctionSymbol init = units.get(i).init();
            if (init != null) {
                if (init.getReturnType() != null) throw new IllegalStateException("module init returns a value");
                calls.add(_functionNames.get(init));
                inModule.add(true);
            }
        }
        calls.add(initialiserName(units.size() - 1));
        inModule.add(false);
        for (String name : List.of("init", "main")) {
            for (FunctionSymbol function : _program.main().functions().keySet()) {
                if (function.getModuleName() == null && function.getName().equals(name)
                        && function.getParameters().isEmpty()) {
                    if (function.getReturnType() != null) {
                        throw new IllegalStateException("entry point " + name + " returns a value");
                    }
                    calls.add(_functionNames.get(function));
                    inModule.add(false);
                }
            }
        }
        for (int i = 0; i < calls.size(); i++) {
            program.append("  call void ").append(calls.get(i)).append("()\n")
                    .append("  %pending.").append(i).append(" = load i8, ptr @siyo_rt_pending_flag\n")
                    .append("  %raised.").append(i).append(" = icmp ne i8 %pending.").append(i).append(", 0\n")
                    .append("  br i1 %raised.").append(i).append(", label %")
                    .append(inModule.get(i) ? "uncaught.module" : "uncaught")
                    .append(", label %ran.").append(i).append('\n')
                    .append("ran.").append(i).append(":\n");
        }
        program.append("  ret void\n")
                .append("uncaught:\n")
                .append("  call void @siyo_rt_uncaught()\n")
                .append("  unreachable\n");
        if (inModule.contains(true)) {
            program.append("uncaught.module:\n")
                    .append("  call void @siyo_rt_uncaught_in_initializer()\n")
                    .append("  unreachable\n");
        }
        return program.append("}\n").toString();
    }

    // --- statements ----------------------------------------------------------

    private void statement(BoundStatement statement) {
        switch (statement) {
            case BoundBlockStatement block -> block.getStatements().forEach(this::statement);
            case BoundVariableDeclaration declaration -> {
                VariableSymbol variable = declaration.getVariable();
                Value value = coerce(expression(declaration.getInitializer()), variable.getType());
                // In the global initialiser, a local of a try expression's body has a slot.
                String target = _function == null && _globals.containsKey(variable)
                        ? _globals.get(variable) : slotFor(variable);
                store(value, target);
            }
            case BoundExpressionStatement expression -> {
                // File-level declarations leave a constant placeholder; it has no effect.
                if (!(expression.getExpression() instanceof BoundLiteralExpression)) {
                    expression(expression.getExpression());
                }
            }
            case BoundLabelStatement label -> {
                String name = labelName(label.getLabel());
                if (!_terminated) terminate("br label %" + name);
                startBlock(name);
            }
            case BoundGotoStatement jump -> terminate("br label %" + labelName(jump.getLabel()));
            case BoundConditionalGotoStatement jump -> {
                Value condition = expression(jump.getCondition());
                String target = labelName(jump.getLabel());
                String next = newBlock("next");
                terminate(jump.getJumpIfTrue()
                        ? "br i1 " + condition.ref() + ", label %" + target + ", label %" + next
                        : "br i1 " + condition.ref() + ", label %" + next + ", label %" + target);
                startBlock(next);
            }
            case BoundReturnStatement ret -> {
                if (ret.getExpression() == null) {
                    terminate("ret void");
                } else {
                    Value value = coerce(expression(ret.getExpression()), _function.getReturnType());
                    terminate("ret " + LlvmTypes.of(value.type()) + " " + value.ref());
                }
            }
            case BoundTryCatchStatement tryCatch -> tryCatch(tryCatch);
            case BoundThrowStatement raise -> {
                Value payload = coerce(expression(raise.getExpression()), Object.class);
                instructionNoResult("call void @siyo_rt_throw(ptr " + payload.ref() + ")");
                terminate("br label %" + handler());
            }
            default -> throw unsupported(statement.getType().toString());
        }
    }

    /**
     * The try body runs with the catch block as its handler. The catch block
     * takes the payload from the runtime (clearing the pending exception) and
     * narrows it to the catch variable's type; a failing cast raises to the
     * enclosing handler, as the JVM's handler code is outside its own range.
     */
    private void tryCatch(BoundTryCatchStatement tryCatch) {
        String catchBlock = newBlock("catch");
        String end = newBlock("try.end");
        _handlers.push(catchBlock);
        statement(tryCatch.getTryBody());
        _handlers.pop();
        if (!_terminated) terminate("br label %" + end);

        startBlock(catchBlock);
        VariableSymbol variable = tryCatch.getErrorVariable();
        Value payload = new Value(instruction("call ptr @siyo_rt_catch()"), Object.class);
        store(coerce(payload, variable.getType()), slotFor(variable));
        statement(tryCatch.getCatchBody());
        if (!_terminated) terminate("br label %" + end);
        startBlock(end);
    }

    /**
     * As the JVM backend does: each body's last expression is its value (null
     * if the body ends in a statement), boxed and then narrowed to the
     * expression's type after the join, outside the try, so a failing cast
     * raises to the enclosing handler. The narrowing is done at the end of
     * each body instead, with the enclosing handler, and the value stored
     * into a slot of the expression's type, so an int or long value whose
     * type already matches is never boxed.
     */
    private Value tryExpression(BoundTryExpression node) {
        Class<?> type = node.getClassType();
        String slot = "%t" + _temps++;
        _allocas.append("  ").append(slot).append(" = alloca ").append(LlvmTypes.of(type)).append('\n');
        String catchBlock = newBlock("catch");
        String join = newBlock("try.value");

        _handlers.push(catchBlock);
        Value tryValue = bodyValue(node.getTryBody());
        _handlers.pop();
        storeTail(tryValue, type, slot, join);

        startBlock(catchBlock);
        VariableSymbol variable = node.getErrorVariable();
        Value payload = new Value(instruction("call ptr @siyo_rt_catch()"), Object.class);
        store(coerce(payload, variable.getType()), slotFor(variable));
        storeTail(bodyValue(node.getCatchBody()), type, slot, join);

        startBlock(join);
        return new Value(instruction("load " + LlvmTypes.of(type) + ", ptr " + slot), type);
    }

    /** Emits a lowered body; returns its last expression's value, or null if it ends in a statement. */
    private Value bodyValue(BoundStatement body) {
        List<BoundStatement> statements = body instanceof BoundBlockStatement block ? block.getStatements() : List.of(body);
        if (!statements.isEmpty() && statements.getLast() instanceof BoundExpressionStatement tail) {
            statements.subList(0, statements.size() - 1).forEach(this::statement);
            return expression(tail.getExpression());
        }
        statements.forEach(this::statement);
        return new Value("null", Object.class);
    }

    /** Narrows a body's value to the expression's type and stores it; nothing if the body cannot complete. */
    private void storeTail(Value value, Class<?> type, String slot, String join) {
        if (_terminated) return;
        Value narrowed;
        if (value.type() == type || type == Object.class || (value.type() == Integer.class && type == Long.class)) {
            // Boxing then unboxing to the same type, or unboxLong of an Integer, which widens.
            narrowed = coerce(value, type);
        } else {
            narrowed = checkedCast(coerce(value, Object.class), type);
        }
        store(narrowed, slot);
        terminate("br label %" + join);
    }

    /** The block a pending exception goes to from here. */
    private String handler() {
        if (!_handlers.isEmpty()) return _handlers.peek();
        _unwindUsed = true;
        return "unwind";
    }

    /** After a call that can raise: goes to the handler if an exception is pending. */
    private void checkPending() {
        String flag = instruction("load i8, ptr @siyo_rt_pending_flag");
        String raised = instruction("icmp ne i8 " + flag + ", 0");
        String next = newBlock("ok");
        terminate("br i1 " + raised + ", label %" + handler() + ", label %" + next);
        startBlock(next);
    }

    /** A call instruction to a runtime function that may raise, followed by the check. */
    private String raisingCall(String text) {
        String result = instruction(text);
        checkPending();
        return result;
    }

    // --- expressions ---------------------------------------------------------

    private Value expression(BoundExpression expression) {
        return switch (expression) {
            case BoundLiteralExpression literal -> literal(literal.getValue());
            case BoundVariableExpression variable -> {
                VariableSymbol symbol = variable.getVariable();
                String type = LlvmTypes.of(symbol.getType());
                String t = instruction("load " + type + ", ptr " + addressOf(symbol));
                yield new Value(t, symbol.getType());
            }
            case BoundAssignmentExpression assignment -> {
                VariableSymbol symbol = assignment.getVariable();
                Value value = coerce(expression(assignment.getExpression()), symbol.getType());
                store(value, addressOf(symbol));
                yield value;
            }
            case BoundUnaryExpression unary -> unary(unary);
            case BoundBinaryExpression binary -> binary(binary);
            case BoundConversionExpression conversion ->
                    coerce(expression(conversion.getExpression()), conversion.getClassType());
            case BoundCallExpression call -> call(call);
            case BoundTryExpression tryExpression -> tryExpression(tryExpression);
            case BoundArrayLiteralExpression array -> arrayLiteral(array);
            case BoundIndexExpression index when index.getTarget().getClassType() == String.class -> {
                // charAt, as a one-unit string.
                Value target = requireType(expression(index.getTarget()), String.class);
                Value position = requireType(expression(index.getIndex()), Integer.class);
                String unit = raisingCall("call ptr @siyo_rt_str_char_at(ptr " + target.ref() + ", i32 " + position.ref() + ")");
                yield coerce(new Value(unit, String.class), index.getClassType());
            }
            case BoundIndexExpression index when index.getTarget().getClassType() == SiyoMap.class -> {
                Value target = requireType(expression(index.getTarget()), SiyoMap.class);
                Value key = coerce(expression(index.getIndex()), Object.class);
                String item = raisingCall("call ptr @siyo_rt_map_get(ptr " + target.ref() + ", ptr " + key.ref() + ")");
                yield coerce(new Value(item, Object.class, "map_get"), index.getClassType());
            }
            case BoundIndexExpression index -> {
                // An erased target is checkcast to List first, as the JVM backend does.
                Value target = coerce(expression(index.getTarget()), SiyoArray.class);
                Value position = requireType(expression(index.getIndex()), Integer.class);
                String item = raisingCall("call ptr @siyo_rt_array_get(ptr " + target.ref() + ", i32 " + position.ref() + ")");
                yield coerce(new Value(item, Object.class), index.getClassType());
            }
            case BoundIndexAssignmentExpression assignment
                    when assignment.getTarget().getClassType() == SiyoMap.class -> {
                // Map.put; the expression's value is the value stored.
                Value target = requireType(expression(assignment.getTarget()), SiyoMap.class);
                Value key = coerce(expression(assignment.getIndex()), Object.class);
                Value value = expression(assignment.getValue());
                Value item = coerce(value, Object.class);
                instructionNoResult("call void @siyo_rt_map_put(ptr " + target.ref() + ", ptr " + key.ref() + ", ptr "
                        + item.ref() + ")");
                checkPending();
                yield value;
            }
            case BoundIndexAssignmentExpression assignment -> indexAssignment(assignment);
            case BoundMapLiteralExpression literal -> mapLiteral(literal);
            case BoundJavaMethodCallExpression call when call.getTarget() != null
                    && call.getTarget().getClassType() == SiyoMap.class -> mapMethod(call);
            case BoundStructLiteralExpression literal -> structLiteral(literal);
            case BoundMemberAccessExpression access -> {
                Value target = requireType(expression(access.getTarget()), SiyoStruct.class);
                String item = raisingCall("call ptr @siyo_rt_struct_get(ptr " + target.ref() + ", ptr "
                        + _strings.nameOf(access.getMemberName()) + ")");
                yield fieldValue(new Value(item, Object.class), access.getClassType());
            }
            case BoundMemberAssignmentExpression assignment -> {
                // Map.put; the expression's value is the value stored, as the JVM backend's dup.
                Value target = requireType(expression(assignment.getTarget()), SiyoStruct.class);
                Value value = expression(assignment.getValue());
                Value item = coerce(value, Object.class);
                instructionNoResult("call void @siyo_rt_struct_set(ptr " + target.ref() + ", ptr "
                        + _strings.nameOf(assignment.getMemberName()) + ", ptr " + item.ref() + ")");
                checkPending();
                yield value;
            }
            default -> throw unsupported(expression.getType().toString());
        };
    }

    /**
     * A new array with room for the elements, then each element boxed and
     * appended in order. The array is not null, so the appends (unlike push)
     * cannot raise and need no pending check; an element's own evaluation
     * still checks after whatever in it can raise.
     */
    private Value arrayLiteral(BoundArrayLiteralExpression node) {
        List<BoundExpression> elements = node.getElements();
        String array = instruction("call ptr @siyo_rt_array_new(i32 " + elements.size() + ")");
        for (BoundExpression element : elements) {
            Value item = coerce(expression(element), Object.class);
            instructionNoResult("call void @siyo_rt_array_append(ptr " + array + ", ptr " + item.ref() + ")");
        }
        return new Value(array, SiyoArray.class);
    }

    /**
     * A new map, then each key and value, boxed, inserted in the literal's
     * order (a repeated key keeps its first place and its last value). The
     * map is not null, so the inserts cannot raise.
     */
    private Value mapLiteral(BoundMapLiteralExpression node) {
        String map = instruction("call ptr @siyo_rt_map_new()");
        List<BoundExpression> keys = node.getKeys();
        List<BoundExpression> values = node.getValues();
        for (int i = 0; i < keys.size(); i++) {
            Value key = coerce(expression(keys.get(i)), Object.class);
            Value value = coerce(expression(values.get(i)), Object.class);
            instructionNoResult("call void @siyo_rt_map_insert(ptr " + map + ", ptr " + key.ref() + ", ptr "
                    + value.ref() + ")");
        }
        return new Value(map, SiyoMap.class);
    }

    /**
     * A SiyoMap method the validator accepted. A string key makes the binder
     * choose the {@code get(String)} overload, which the JVM's message for an
     * unboxed null names; the other overloads behave alike.
     */
    private Value mapMethod(BoundJavaMethodCallExpression call) {
        Value target = requireType(expression(call.getTarget()), SiyoMap.class);
        List<String> args = new java.util.ArrayList<>();
        for (BoundExpression argument : call.getArguments()) {
            args.add("ptr " + coerce(expression(argument), Object.class).ref());
        }
        String list = args.isEmpty() ? "" : ", " + String.join(", ", args);
        String receiver = "ptr " + target.ref() + list;
        return switch (call.getMethodName()) {
            case "get" -> {
                String[] params = call.getResolvedSignature().getParamDescriptors();
                String origin = params.length == 1 && params[0].equals("Ljava/lang/String;") ? "map_get_str" : "map_get";
                yield new Value(raisingCall("call ptr @siyo_rt_map_get(" + receiver + ")"), Object.class, origin);
            }
            case "set" -> {
                instructionNoResult("call void @siyo_rt_map_put(" + receiver + ")");
                checkPending();
                yield new Value(null, null);
            }
            case "has" -> new Value(raisingCall("call zeroext i1 @siyo_rt_map_has(" + receiver + ")"), Boolean.class);
            case "remove" -> {
                // SiyoMap.remove returns nothing.
                raisingCall("call ptr @siyo_rt_map_remove(" + receiver + ")");
                yield new Value(null, null);
            }
            case "size" -> new Value(raisingCall("call i32 @siyo_rt_map_size(" + receiver + ")"), Integer.class);
            case "keys" -> new Value(raisingCall("call ptr @siyo_rt_map_keys(" + receiver + ")"), SiyoArray.class);
            default -> throw unsupported("map method " + call.getMethodName());
        };
    }

    /**
     * A new struct with the literal's field names, then each value, in the
     * literal's order, boxed and stored. The struct is not null, so the stores
     * cannot raise.
     */
    private Value structLiteral(BoundStructLiteralExpression node) {
        Map<String, BoundExpression> fields = node.getFieldValues();
        String names = "null";
        if (!fields.isEmpty()) {
            names = "@siyo.fields." + _fieldNames.size();
            StringBuilder list = new StringBuilder();
            for (String field : fields.keySet()) {
                if (!list.isEmpty()) list.append(", ");
                list.append("ptr ").append(_strings.nameOf(field));
            }
            _fieldNames.add(names + " = private unnamed_addr constant [" + fields.size() + " x ptr] [" + list + "]");
        }
        String struct = instruction("call ptr @siyo_rt_struct_new(ptr " + _strings.nameOf(node.getStructType().getName())
                + ", i32 " + fields.size() + ", ptr " + names + ")");
        int index = 0;
        for (BoundExpression field : fields.values()) {
            Value item = coerce(expression(field), Object.class);
            instructionNoResult("call void @siyo_rt_struct_init(ptr " + struct + ", i32 " + index++ + ", ptr "
                    + item.ref() + ")");
        }
        return new Value(struct, SiyoStruct.class);
    }

    /**
     * A field's value narrowed to the type the binder gave the read. Unboxing
     * a null into an int or bool raises the NullPointerException whose
     * message names {@code Map.get}, as the JVM's does for a field.
     */
    private Value fieldValue(Value item, Class<?> type) {
        if (type == Integer.class) {
            return new Value(raisingCall("call i32 @siyo_rt_unbox_int_field(ptr " + item.ref() + ")"), Integer.class);
        }
        if (type == Boolean.class) {
            return new Value(raisingCall("call zeroext i1 @siyo_rt_unbox_bool_field(ptr " + item.ref() + ")"), Boolean.class);
        }
        return coerce(item, type);
    }

    /**
     * {@code a[i] = v} is {@code List.set}, whose result, the element replaced,
     * the JVM backend narrows to the type of {@code v}, even when the value is
     * discarded: replacing a string with an int in an {@code any[]} raises
     * ClassCastException after the store. That element is the expression's
     * value.
     */
    private Value indexAssignment(BoundIndexAssignmentExpression node) {
        Value target = requireType(expression(node.getTarget()), SiyoArray.class);
        Value position = requireType(expression(node.getIndex()), Integer.class);
        Value value = expression(node.getValue());
        Class<?> type = value.type();
        Value item = coerce(value, Object.class);
        String old = raisingCall("call ptr @siyo_rt_array_set(ptr " + target.ref() + ", i32 " + position.ref()
                + ", ptr " + item.ref() + ")");
        Value replaced = new Value(old, Object.class);
        return type == Object.class ? replaced : checkedCast(replaced, type);
    }

    private Value literal(Object value) {
        return switch (value) {
            case Integer i -> new Value(i.toString(), Integer.class);
            case Long l -> new Value(l.toString(), Long.class);
            case Boolean b -> new Value(b.toString(), Boolean.class);
            case String s -> new Value(_strings.nameOf(s), String.class);
            case null -> new Value("null", Object.class);
            default -> throw unsupported(value.getClass().getSimpleName() + " literal");
        };
    }

    private Value unary(BoundUnaryExpression unary) {
        Value operand = expression(unary.getOperand());
        String type = LlvmTypes.of(operand.type());
        return switch (unary.getOperator().getType()) {
            case Identity -> requireType(operand, Integer.class);
            case Negation -> new Value(instruction("sub " + type + " 0, " + requireType(operand, Integer.class).ref()), Integer.class);
            case OnesComplement -> new Value(instruction("xor " + type + " " + requireType(operand, Integer.class).ref() + ", -1"), Integer.class);
            case LogicalNegation -> new Value(instruction("xor i1 " + requireType(operand, Boolean.class).ref() + ", true"), Boolean.class);
        };
    }

    private Value binary(BoundBinaryExpression binary) {
        BoundBinaryOperatorType op = binary.getOperator().getType();
        if (op == BoundBinaryOperatorType.LogicalAnd || op == BoundBinaryOperatorType.LogicalOr) {
            return shortCircuit(binary, op == BoundBinaryOperatorType.LogicalAnd);
        }
        Value left = expression(binary.getLeft());
        Value right = expression(binary.getRight());

        if (op == BoundBinaryOperatorType.Addition && (left.type() == String.class || right.type() == String.class)) {
            String l = text(left).ref();
            String r = text(right).ref();
            return new Value(instruction("call ptr @siyo_rt_str_concat(ptr " + l + ", ptr " + r + ")"), String.class);
        }
        if (left.type() == Object.class || right.type() == Object.class) {
            // Objects.equals over both values boxed, as the JVM backend compares an erased operand.
            if (op != BoundBinaryOperatorType.Equals && op != BoundBinaryOperatorType.NotEquals) {
                throw unsupported("operator " + op + " on any");
            }
            String l = coerce(left, Object.class).ref();
            String r = coerce(right, Object.class).ref();
            String equal = instruction("call zeroext i1 @siyo_rt_any_equals(ptr " + l + ", ptr " + r + ")");
            return op == BoundBinaryOperatorType.Equals ? new Value(equal, Boolean.class)
                    : new Value(instruction("xor i1 " + equal + ", true"), Boolean.class);
        }
        if (left.type() == String.class || right.type() == String.class) {
            requireType(left, String.class);
            requireType(right, String.class);
            if (op == BoundBinaryOperatorType.Equals || op == BoundBinaryOperatorType.NotEquals) {
                String equal = instruction("call zeroext i1 @siyo_rt_str_eq(ptr " + left.ref() + ", ptr " + right.ref() + ")");
                return op == BoundBinaryOperatorType.Equals ? new Value(equal, Boolean.class)
                        : new Value(instruction("xor i1 " + equal + ", true"), Boolean.class);
            }
            String predicate = switch (op) {
                case LessThan -> "slt";
                case LessOrEqualsThan -> "sle";
                case GreaterThan -> "sgt";
                case GreaterOrEqualsThen -> "sge";
                default -> throw unsupported("string operator " + op);
            };
            // compareTo's sign, over UTF-16 code units.
            String order = raisingCall("call i32 @siyo_rt_str_compare(ptr " + left.ref() + ", ptr " + right.ref() + ")");
            return compare(predicate, "i32", order, "0");
        }
        if (left.type() == Boolean.class || right.type() == Boolean.class) {
            requireType(left, Boolean.class);
            requireType(right, Boolean.class);
            String instruction = switch (op) {
                case BitwiseAnd -> "and";
                case BitwiseOr -> "or";
                case BitwiseXor -> "xor";
                case Equals -> "icmp eq";
                case NotEquals -> "icmp ne";
                default -> throw unsupported("bool operator " + op);
            };
            return new Value(instruction(instruction + " i1 " + left.ref() + ", " + right.ref()), Boolean.class);
        }

        // int and long; a mixed pair widens to long.
        Class<?> type = left.type() == Long.class || right.type() == Long.class ? Long.class : Integer.class;
        left = coerce(left, type);
        right = coerce(right, type);
        String t = LlvmTypes.of(type);
        String l = left.ref();
        String r = right.ref();
        return switch (op) {
            case Addition -> new Value(instruction("add " + t + " " + l + ", " + r), type);
            case Subtraction -> new Value(instruction("sub " + t + " " + l + ", " + r), type);
            case Multiplication -> new Value(instruction("mul " + t + " " + l + ", " + r), type);
            case Division -> new Value(divide(t, l, r, false), type);
            case Modulo -> new Value(divide(t, l, r, true), type);
            case BitwiseAnd -> new Value(instruction("and " + intOnly(type, op) + " " + l + ", " + r), type);
            case BitwiseOr -> new Value(instruction("or " + intOnly(type, op) + " " + l + ", " + r), type);
            case BitwiseXor -> new Value(instruction("xor " + intOnly(type, op) + " " + l + ", " + r), type);
            case LeftShift -> new Value(shift("shl", intOnly(type, op), l, r), type);
            case RightShift -> new Value(shift("ashr", intOnly(type, op), l, r), type);
            case Equals -> compare("eq", t, l, r);
            case NotEquals -> compare("ne", t, l, r);
            case LessThan -> compare("slt", t, l, r);
            case LessOrEqualsThan -> compare("sle", t, l, r);
            case GreaterThan -> compare("sgt", t, l, r);
            case GreaterOrEqualsThen -> compare("sge", t, l, r);
            default -> throw unsupported("integer operator " + op);
        };
    }

    private static String intOnly(Class<?> type, BoundBinaryOperatorType op) {
        if (type != Integer.class) throw unsupported(op + " on long");
        return "i32";
    }

    private Value compare(String predicate, String type, String l, String r) {
        return new Value(instruction("icmp " + predicate + " " + type + " " + l + ", " + r), Boolean.class);
    }

    /** Java's shift: the count is taken modulo the bit width. */
    private String shift(String instruction, String type, String l, String r) {
        String count = instruction("and " + type + " " + r + ", 31");
        return instruction(instruction + " " + type + " " + l + ", " + count);
    }

    /**
     * Java's {@code /} and {@code %}: a zero divisor raises ArithmeticException;
     * {@code MIN / -1} is {@code MIN} and {@code MIN % -1} is 0. LLVM's
     * {@code sdiv}/{@code srem} are undefined for both, so the divisor is
     * replaced by 1 when it is -1 and the quotient negated instead.
     */
    private String divide(String type, String l, String r, boolean remainder) {
        String zero = instruction("icmp eq " + type + " " + r + ", 0");
        String fail = newBlock("div.zero");
        String ok = newBlock("div.ok");
        terminate("br i1 " + zero + ", label %" + fail + ", label %" + ok);
        startBlock(fail);
        instructionNoResult("call void @siyo_rt_div_by_zero()");
        terminate("br label %" + handler());
        startBlock(ok);
        String minusOne = instruction("icmp eq " + type + " " + r + ", -1");
        String divisor = instruction("select i1 " + minusOne + ", " + type + " 1, " + type + " " + r);
        if (remainder) {
            // x % 1 == 0 == MIN % -1, so the safe divisor alone gives Java's result.
            return instruction("srem " + type + " " + l + ", " + divisor);
        }
        String quotient = instruction("sdiv " + type + " " + l + ", " + divisor);
        String negated = instruction("sub " + type + " 0, " + l);
        return instruction("select i1 " + minusOne + ", " + type + " " + negated + ", " + type + " " + quotient);
    }

    private Value shortCircuit(BoundBinaryExpression binary, boolean and) {
        Value left = requireType(expression(binary.getLeft()), Boolean.class);
        String rhs = newBlock(and ? "and.rhs" : "or.rhs");
        String end = newBlock(and ? "and.end" : "or.end");
        String leftBlock = branch(and
                ? "br i1 " + left.ref() + ", label %" + rhs + ", label %" + end
                : "br i1 " + left.ref() + ", label %" + end + ", label %" + rhs);
        startBlock(rhs);
        Value right = requireType(expression(binary.getRight()), Boolean.class);
        String rightBlock = branch("br label %" + end);
        startBlock(end);
        String result = instruction("phi i1 [ " + (and ? "false" : "true") + ", %" + leftBlock + " ], [ "
                + right.ref() + ", %" + rightBlock + " ]");
        return new Value(result, Boolean.class);
    }

    private Value call(BoundCallExpression call) {
        FunctionSymbol function = call.getFunction();
        if (function == BuiltinFunctions.PRINT || function == BuiltinFunctions.PRINTLN) {
            if (call.getArguments().size() != 1) throw unsupported(function.getName() + " arity");
            Value value = expression(call.getArguments().get(0));
            Class<?> type = value.type();
            String printer = type == Integer.class ? "@siyo_rt_print_int(i32 "
                    : type == Long.class ? "@siyo_rt_print_long(i64 "
                    : type == Boolean.class ? "@siyo_rt_print_bool(i1 zeroext "
                    : type == String.class ? "@siyo_rt_print_str(ptr "
                    : type == Object.class || type == SiyoArray.class || type == SiyoStruct.class
                            || type == SiyoMap.class ? "@siyo_rt_print_any(ptr "
                    : null;
            if (printer == null) throw unsupported("printing " + type);
            instructionNoResult("call void " + printer + value.ref() + ")");
            if (function == BuiltinFunctions.PRINTLN) instructionNoResult("call void @siyo_rt_print_newline()");
            return new Value(null, null);
        }

        if (function == BuiltinFunctions.ERROR) {
            // error(x) throws x itself, as the JVM backend does: boxed, not cast to string.
            if (call.getArguments().size() != 1) throw unsupported("error arity");
            Value payload = coerce(expression(call.getArguments().get(0)), Object.class);
            instructionNoResult("call void @siyo_rt_throw(ptr " + payload.ref() + ")");
            terminate("br label %" + handler());
            return new Value(null, null);
        }

        if (function == BuiltinFunctions.TO_STRING) {
            if (call.getArguments().size() != 1) throw unsupported("toString arity");
            Value value = expression(call.getArguments().get(0));
            if (value.type() == String.class) {
                return new Value(instruction("call ptr @siyo_rt_str_of_string(ptr " + value.ref() + ")"), String.class);
            }
            return text(value);
        }

        if (function == BuiltinFunctions.TYPE_NAME) {
            if (call.getArguments().size() != 1) throw unsupported("typeName arity");
            Value value = coerce(expression(call.getArguments().get(0)), Object.class);
            return new Value(instruction("call ptr @siyo_rt_type_name(ptr " + value.ref() + ")"), String.class);
        }

        if (function == BuiltinFunctions.TO_LONG) {
            return coerce(requireType(expression(call.getArguments().get(0)), Integer.class), Long.class);
        }
        if (function == BuiltinFunctions.TO_INT_LONG) {
            Value value = requireType(expression(call.getArguments().get(0)), Long.class);
            return new Value(instruction("trunc i64 " + value.ref() + " to i32"), Integer.class);
        }
        if (function == BuiltinFunctions.NEW_MAP) {
            if (!call.getArguments().isEmpty()) throw unsupported("map arity");
            return new Value(instruction("call ptr @siyo_rt_map_new()"), SiyoMap.class);
        }
        if (function == BuiltinFunctions.MAP_KEYS) {
            // for k in m iterates over $mapKeys(m), a fresh array of the keys.
            if (call.getArguments().size() != 1) throw unsupported("$mapKeys arity");
            Value map = requireType(expression(call.getArguments().get(0)), SiyoMap.class);
            return new Value(raisingCall("call ptr @siyo_rt_map_keys(ptr " + map.ref() + ")"), SiyoArray.class);
        }
        if (function == BuiltinFunctions.LEN && call.getArguments().size() == 1
                && call.getArguments().get(0).getClassType() == SiyoMap.class) {
            // len(m) is 0 for a null map, as SiyoRuntime.lengthOf is.
            Value map = requireType(expression(call.getArguments().get(0)), SiyoMap.class);
            return new Value(instruction("call i32 @siyo_rt_map_len(ptr " + map.ref() + ")"), Integer.class);
        }
        Value arrayResult = arrayBuiltin(call);
        if (arrayResult != null) return arrayResult;
        String runtime = RUNTIME_BUILTINS.get(function);
        if (runtime != null) return runtimeCall(runtime, function, call.getArguments());

        FunctionSymbol target = _program.target(function);
        String name = target == null ? null : _functionNames.get(target);
        if (name == null) throw unsupported("call to " + function.getName());
        function = target;
        StringBuilder args = new StringBuilder();
        List<ParameterSymbol> parameters = function.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            Value argument = argument(expression(call.getArguments().get(i)), parameters.get(i).getType());
            if (i > 0) args.append(", ");
            args.append(LlvmTypes.of(argument.type())).append(' ').append(argument.ref());
        }
        // notail: a self-call must stay a call, so unbounded recursion reaches
        // the stack guard as it overflows the JVM's stack, instead of becoming a loop.
        if (function.getReturnType() == null) {
            instructionNoResult("notail call void " + name + "(" + args + ")");
            checkPending();
            return new Value(null, null);
        }
        String t = raisingCall("notail call " + LlvmTypes.of(function.getReturnType()) + " " + name + "(" + args + ")");
        return new Value(t, function.getReturnType());
    }

    /**
     * An argument coerced to its parameter's type. The JVM backend casts an
     * any passed to a struct parameter to {@code LinkedHashMap}, not to
     * {@code Map} as elsewhere, which shows in the ClassCastException.
     */
    private Value argument(Value value, Class<?> parameterType) {
        if (parameterType == SiyoStruct.class && value.type() == Object.class && !value.ref().equals("null")) {
            return new Value(raisingCall("call ptr @siyo_rt_cast_struct_arg(ptr " + value.ref() + ")"), SiyoStruct.class);
        }
        return coerce(value, parameterType);
    }

    /**
     * len of an array, push, pop and removeAt; null for any other call. A
     * pushed value is boxed; pop's element is narrowed to the call's type,
     * which the binder sets to the element type it tracks for the array.
     */
    private Value arrayBuiltin(BoundCallExpression call) {
        FunctionSymbol function = call.getFunction();
        List<BoundExpression> arguments = call.getArguments();
        boolean arrayLen = function == BuiltinFunctions.LEN && arguments.size() == 1
                && arguments.get(0).getClassType() == SiyoArray.class;
        if (!arrayLen && function != BuiltinFunctions.PUSH && function != BuiltinFunctions.POP
                && function != BuiltinFunctions.REMOVE_AT) {
            return null;
        }
        if (arguments.size() != function.getParameters().size()) throw unsupported(function.getName() + " arity");
        String array = requireType(expression(arguments.get(0)), SiyoArray.class).ref();
        if (arrayLen) {
            return new Value(raisingCall("call i32 @siyo_rt_array_len(ptr " + array + ")"), Integer.class);
        }
        if (function == BuiltinFunctions.POP) {
            String item = raisingCall("call ptr @siyo_rt_array_pop(ptr " + array + ")");
            return coerce(new Value(item, Object.class), call.getClassType());
        }
        if (function == BuiltinFunctions.PUSH) {
            Value item = coerce(expression(arguments.get(1)), Object.class);
            instructionNoResult("call void @siyo_rt_array_push(ptr " + array + ", ptr " + item.ref() + ")");
        } else {
            Value index = requireType(expression(arguments.get(1)), Integer.class);
            instructionNoResult("call void @siyo_rt_array_remove_at(ptr " + array + ", i32 " + index.ref() + ")");
        }
        checkPending();
        return new Value(null, null);
    }

    /**
     * Builtins that are one call into the runtime, which raises Java's
     * exception where the JVM would (null receivers included). toLong(int)
     * and toInt(long) are emitted inline as sext and trunc. Keyed by
     * identity, as FunctionSymbol.equals compares name and arity only.
     */
    private static final Map<FunctionSymbol, String> RUNTIME_BUILTINS = new IdentityHashMap<>();

    static {
        RUNTIME_BUILTINS.put(BuiltinFunctions.LEN, "@siyo_rt_str_len");
        RUNTIME_BUILTINS.put(BuiltinFunctions.SUBSTRING, "@siyo_rt_str_substring");
        RUNTIME_BUILTINS.put(BuiltinFunctions.CONTAINS, "@siyo_rt_str_contains");
        RUNTIME_BUILTINS.put(BuiltinFunctions.INDEX_OF, "@siyo_rt_str_index_of");
        RUNTIME_BUILTINS.put(BuiltinFunctions.STARTS_WITH, "@siyo_rt_str_starts_with");
        RUNTIME_BUILTINS.put(BuiltinFunctions.ENDS_WITH, "@siyo_rt_str_ends_with");
        RUNTIME_BUILTINS.put(BuiltinFunctions.REPLACE, "@siyo_rt_str_replace");
        RUNTIME_BUILTINS.put(BuiltinFunctions.TRIM, "@siyo_rt_str_trim");
        RUNTIME_BUILTINS.put(BuiltinFunctions.TO_UPPER, "@siyo_rt_str_to_upper");
        RUNTIME_BUILTINS.put(BuiltinFunctions.TO_LOWER, "@siyo_rt_str_to_lower");
        RUNTIME_BUILTINS.put(BuiltinFunctions.CHR, "@siyo_rt_chr");
        RUNTIME_BUILTINS.put(BuiltinFunctions.ORD, "@siyo_rt_ord");
        RUNTIME_BUILTINS.put(BuiltinFunctions.PARSE_INT, "@siyo_rt_parse_int");
        RUNTIME_BUILTINS.put(BuiltinFunctions.TO_INT_STR, "@siyo_rt_parse_int");
        RUNTIME_BUILTINS.put(BuiltinFunctions.PARSE_LONG, "@siyo_rt_parse_long");
        RUNTIME_BUILTINS.put(BuiltinFunctions.RANGE, "@siyo_rt_range");
        RUNTIME_BUILTINS.put(BuiltinFunctions.SPLIT, "@siyo_rt_str_split");
    }

    /** Calls {@code runtime} with the arguments coerced to the builtin's parameter types. */
    private Value runtimeCall(String runtime, FunctionSymbol function, List<BoundExpression> arguments) {
        StringBuilder args = new StringBuilder();
        List<ParameterSymbol> parameters = function.getParameters();
        if (arguments.size() != parameters.size()) throw unsupported(function.getName() + " arity");
        for (int i = 0; i < parameters.size(); i++) {
            Class<?> type = parameters.get(i).getType();
            // len's parameter is declared as any value; the validator admits strings only.
            Value argument = type == Object.class
                    ? requireType(expression(arguments.get(i)), String.class)
                    : coerce(expression(arguments.get(i)), type);
            if (i > 0) args.append(", ");
            args.append(abiType(argument.type())).append(' ').append(argument.ref());
        }
        Class<?> result = function.getReturnType();
        String text = "call " + abiType(result) + " " + runtime + "(" + args + ")";
        return new Value(RuntimeIr.RAISING.contains(runtime) ? raisingCall(text) : instruction(text), result);
    }

    /** The type as it crosses into C: {@code _Bool} is {@code zeroext i1}. */
    private static String abiType(Class<?> type) {
        String llvm = LlvmTypes.of(type);
        return llvm.equals("i1") ? "zeroext i1" : llvm;
    }

    // --- helpers -------------------------------------------------------------

    /**
     * {@code value} as a string operand of {@code +}: Java's decimal form for
     * int and long, {@code true}/{@code false} for bool, {@code String.valueOf}
     * for an any, an array or a struct. A string is kept as is, null included; concatenation reads
     * null as {@code "null"}. Any other type is refused.
     */
    private Value text(Value value) {
        if (value.type() == String.class) return value;
        String call;
        if (value.type() == Integer.class) {
            call = "call ptr @siyo_rt_str_from_int(i32 ";
        } else if (value.type() == Long.class) {
            call = "call ptr @siyo_rt_str_from_long(i64 ";
        } else if (value.type() == Boolean.class) {
            call = "call ptr @siyo_rt_str_from_bool(i1 zeroext ";
        } else if (value.type() == Object.class || value.type() == SiyoArray.class || value.type() == SiyoStruct.class
                || value.type() == SiyoMap.class) {
            call = "call ptr @siyo_rt_any_to_string(ptr ";
        } else {
            throw unsupported("conversion of " + value.type() + " to text");
        }
        return new Value(instruction(call + value.ref() + ")"), String.class);
    }

    /**
     * {@code value} as type {@code to}: int widens to long; a value becomes an
     * any by boxing (a string already is one); an any becomes a declared type
     * through the runtime's checked unbox or cast.
     */
    private Value coerce(Value value, Class<?> to) {
        Class<?> from = value.type();
        if (from == to) return value;
        if (from == Integer.class && to == Long.class) {
            return new Value(instruction("sext i32 " + value.ref() + " to i64"), Long.class);
        }
        if (to == Object.class) {
            String box = from == Integer.class ? "call ptr @siyo_rt_box_int(i32 "
                    : from == Long.class ? "call ptr @siyo_rt_box_long(i64 "
                    : from == Boolean.class ? "call ptr @siyo_rt_box_bool(i1 zeroext "
                    : null;
            if (from == String.class || from == SiyoArray.class || from == SiyoStruct.class || from == SiyoMap.class) {
                return new Value(value.ref(), Object.class);
            }
            if (box != null) return new Value(instruction(box + value.ref() + ")"), Object.class);
        }
        if (from == Object.class) {
            // The null literal is a string already; unboxed, it raises the JVM's
            // NullPointerException, whose message names the literal. Anything
            // else is checked at run time.
            if (value.ref().equals("null")) {
                if (to == String.class || to == SiyoArray.class || to == SiyoStruct.class || to == SiyoMap.class) {
                    return new Value("null", to);
                }
                if (to == Integer.class || to == Boolean.class) {
                    int tag = to == Integer.class ? 2 : 4; // SIYO_TAG_INT, SIYO_TAG_BOOL
                    instructionNoResult("call void @siyo_rt_unbox_null_literal(i32 " + tag + ")");
                    terminate("br label %" + handler());
                    return new Value(LlvmTypes.zero(to), to);
                }
            }
            return checkedCast(value, to);
        }
        throw unsupported("conversion from " + from + " to " + to);
    }

    /**
     * An any narrowed to {@code to} by the runtime's checked unbox or cast,
     * even for the null literal; an int or bool unbox names the value's null
     * origin when it has one. (unbox_long's message never depends on it.)
     */
    private Value checkedCast(Value value, Class<?> to) {
        requireType(value, Object.class);
        String origin = value.nullOrigin() == null ? "" : "_" + value.nullOrigin();
        String cast = to == Integer.class ? "call i32 @siyo_rt_unbox_int" + origin + "(ptr "
                : to == Long.class ? "call i64 @siyo_rt_unbox_long(ptr "
                : to == Boolean.class ? "call zeroext i1 @siyo_rt_unbox_bool" + origin + "(ptr "
                : to == String.class ? "call ptr @siyo_rt_cast_str(ptr "
                : to == SiyoArray.class ? "call ptr @siyo_rt_cast_array(ptr "
                : to == SiyoStruct.class ? "call ptr @siyo_rt_cast_struct(ptr "
                : to == SiyoMap.class ? "call ptr @siyo_rt_cast_map(ptr "
                : null;
        if (cast == null) throw unsupported("conversion from " + value.type() + " to " + to);
        return new Value(raisingCall(cast + value.ref() + ")"), to);
    }

    private static Value requireType(Value value, Class<?> type) {
        if (value.type() != type) throw unsupported("operand of type " + value.type() + " where " + type + " expected");
        return value;
    }

    private String slotFor(VariableSymbol variable) {
        return _slots.computeIfAbsent(variable, v -> {
            String slot = "%v" + _slots.size();
            _allocas.append("  ").append(slot).append(" = alloca ").append(LlvmTypes.of(v.getType())).append('\n');
            return slot;
        });
    }

    private String addressOf(VariableSymbol variable) {
        String slot = _slots.get(variable);
        if (slot != null) return slot;
        String global = _globals.get(_program.target(variable));
        if (global != null) return global;
        throw unsupported("variable '" + variable.getName() + "' with no storage");
    }

    private void store(Value value, String address) {
        instructionNoResult("store " + LlvmTypes.of(value.type()) + " " + value.ref() + ", ptr " + address);
    }

    private String labelName(LabelSymbol label) {
        return _labels.computeIfAbsent(label, l -> "L" + _labels.size());
    }

    private String newBlock(String hint) {
        return hint + "." + _blocks++;
    }

    private void startBlock(String name) {
        _body.append(name).append(":\n");
        _block = name;
        _terminated = false;
    }

    /** Code after a terminator is unreachable; it gets a block of its own so the IR stays valid. */
    private void ensureOpen() {
        if (_terminated) startBlock(newBlock("dead"));
    }

    private String instruction(String text) {
        ensureOpen();
        String t = "%t" + _temps++;
        _body.append("  ").append(t).append(" = ").append(text).append('\n');
        return t;
    }

    private void instructionNoResult(String text) {
        ensureOpen();
        _body.append("  ").append(text).append('\n');
    }

    /**
     * Terminates the current block with a branch and returns that block's name,
     * the one a phi in a successor must name. It is read after {@link
     * #ensureOpen}: in unreachable code the branch goes into a fresh block.
     */
    private String branch(String text) {
        ensureOpen();
        String from = _block;
        terminate(text);
        return from;
    }

    private void terminate(String text) {
        ensureOpen();
        _body.append("  ").append(text).append('\n');
        _terminated = true;
    }

    private static IllegalStateException unsupported(String what) {
        return new IllegalStateException("SiyoLLVM emitter cannot compile " + what
                + "; the subset validator should have rejected it");
    }
}
