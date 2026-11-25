package siyollvm.ir;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ties {@link RuntimeIr#RAISING} to the runtime: a call to a function that can
 * raise without a pending check after it would let execution continue past
 * an exception with a dummy value.
 */
class RuntimeIrTest {

    /** Declared functions that raise every time; the emitter branches to the handler after them unconditionally. */
    private static final Set<String> ALWAYS_RAISE = Set.of(
            "@siyo_rt_throw", "@siyo_rt_div_by_zero", "@siyo_rt_unbox_null_literal");

    /** Declared functions that never record a pending exception (fatal errors end the process instead). */
    private static final Set<String> NEVER_RAISE = Set.of(
            "@siyo_rt_print_int", "@siyo_rt_print_long", "@siyo_rt_print_bool", "@siyo_rt_print_str",
            "@siyo_rt_print_newline", "@siyo_rt_print_any",
            "@siyo_rt_str_eq", "@siyo_rt_str_concat", "@siyo_rt_str_from_int", "@siyo_rt_str_from_long",
            "@siyo_rt_str_from_bool", "@siyo_rt_str_of_string", "@siyo_rt_parse_int",
            "@siyo_rt_box_int", "@siyo_rt_box_long", "@siyo_rt_box_bool", "@siyo_rt_array_new", "@siyo_rt_array_append", "@siyo_rt_range",
            "@siyo_rt_any_to_string", "@siyo_rt_any_equals",
            "@siyo_rt_struct_new", "@siyo_rt_struct_init", "@siyo_rt_type_name",
            "@siyo_rt_map_new", "@siyo_rt_map_insert", "@siyo_rt_map_len",
            "@siyo_rt_io_eprintln", "@siyo_rt_os_args", "@siyo_rt_os_exit", "@siyo_rt_os_cwd",
            "@siyo_rt_catch", "@siyo_rt_uncaught", "@siyo_rt_uncaught_in_initializer", "@siyo_rt_stack_overflow");

    @Test
    void everyDeclaredRuntimeFunctionIsClassified() {
        Set<String> unclassified = new TreeSet<>();
        Set<String> twice = new TreeSet<>();
        for (String function : declaredFunctions()) {
            int n = (RuntimeIr.RAISING.contains(function) ? 1 : 0) + (ALWAYS_RAISE.contains(function) ? 1 : 0)
                    + (NEVER_RAISE.contains(function) ? 1 : 0);
            if (n == 0) unclassified.add(function);
            if (n > 1) twice.add(function);
        }
        assertEquals(Set.of(), unclassified, "classify as RuntimeIr.RAISING or in this test's lists");
        assertEquals(Set.of(), twice, "classified more than once");
        Set<String> stale = new TreeSet<>(RuntimeIr.RAISING);
        stale.addAll(ALWAYS_RAISE);
        stale.addAll(NEVER_RAISE);
        stale.removeAll(declaredFunctions());
        assertEquals(Set.of(), stale, "classified but not declared");
    }

    /**
     * A raise is an assignment of 1 to {@code siyo_rt_pending_flag}; a
     * function raises if its body does that or calls a function that raises.
     * The classification must agree with the C source both ways.
     */
    @Test
    void classificationAgreesWithTheCSource() throws IOException {
        Map<String, String> bodies = functionBodies(runtimeSource());
        for (String function : declaredFunctions()) {
            assertNotNull(bodies.get(function.substring(1)), "no definition of " + function + " found in siyo_rt.c");
        }
        Set<String> raisers = raisers(bodies);
        assertTrue(raisers.containsAll(Set.of("siyo_rt_raise_java", "siyo_rt_raise_format", "siyo_rt_null_pointer",
                "siyo_rt_throw")), raisers.toString());
        for (String function : declaredFunctions()) {
            boolean raises = raisers.contains(function.substring(1));
            boolean classifiedRaising = RuntimeIr.RAISING.contains(function) || ALWAYS_RAISE.contains(function);
            assertEquals(raises, classifiedRaising, function + (raises
                    ? " can raise in siyo_rt.c but is not classified as raising"
                    : " is classified as raising but siyo_rt.c never raises in it"));
        }
    }

    @Test
    void theCParserFindsTransitiveRaisers() {
        String source = """
                static void raise_it(void) {
                    pending = 0;
                    siyo_rt_pending_flag = 1;
                }
                static int helper(int x) {
                    if (x) raise_it();
                    return x;
                }
                int top(int x) {
                    return helper(x) + 1;
                }
                int quiet(int x) {
                    return x; /* raise_it() in a comment */
                }
                """;
        Map<String, String> bodies = functionBodies(source);
        assertEquals(Set.of("raise_it", "helper", "top", "quiet"), bodies.keySet());
        assertEquals(Set.of("raise_it", "helper", "top"), raisers(bodies));
    }

    private static Set<String> declaredFunctions() {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^\\s*declare [^@\\n]*(@\\w+)\\(").matcher(RuntimeIr.text());
        while (m.find()) names.add(m.group(1));
        assertTrue(names.size() > 30, names.toString());
        return names;
    }

    private static String runtimeSource() throws IOException {
        try (InputStream in = RuntimeIr.class.getResourceAsStream("/siyollvm/runtime/siyo_rt.c")) {
            assertNotNull(in, "siyo_rt.c resource");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The runtime's style: a definition is a line at column 0 ending in
     * {@code name(parameters) {}, and its body ends at the next line that is
     * just {@code }}. Comments are removed first.
     */
    static Map<String, String> functionBodies(String source) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", "");
        Map<String, String> bodies = new HashMap<>();
        Pattern definition = Pattern.compile("^[A-Za-z_][^;=]*?\\b(\\w+)\\s*\\([^;{}]*\\)\\s*\\{\\s*$");
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (current == null) {
                Matcher m = definition.matcher(line);
                if (m.matches()) {
                    current = m.group(1);
                    body.setLength(0);
                } else if (line.matches("[A-Za-z_].*\\(.*") && !line.startsWith("__attribute__")
                        && !line.stripTrailing().endsWith(";")) {
                    // A definition in a form this parser does not know (say, over two lines)
                    // would hide a raiser: fail rather than miss it.
                    throw new AssertionError("unrecognised top-level line in siyo_rt.c: " + line);
                }
            } else if (line.equals("}")) {
                bodies.put(current, body.toString());
                current = null;
            } else {
                body.append(line).append('\n');
            }
        }
        assertEquals(null, current, "unterminated definition");
        return bodies;
    }

    /** The functions that set the pending flag, directly or through a call; a fixpoint. */
    static Set<String> raisers(Map<String, String> bodies) {
        Set<String> raisers = new HashSet<>();
        Pattern raise = Pattern.compile("\\bsiyo_rt_pending_flag\\s*=\\s*1\\b");
        bodies.forEach((name, body) -> {
            if (raise.matcher(body).find()) raisers.add(name);
        });
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : bodies.entrySet()) {
                if (raisers.contains(entry.getKey())) continue;
                for (String callee : List.copyOf(raisers)) {
                    if (Pattern.compile("\\b" + Pattern.quote(callee) + "\\s*\\(").matcher(entry.getValue()).find()) {
                        raisers.add(entry.getKey());
                        changed = true;
                        break;
                    }
                }
            }
        }
        return raisers;
    }
}
