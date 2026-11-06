package siyollvm.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A small structural check of emitted IR, independent of clang: every block
 * ends in a terminator, and every {@code phi} lists exactly the block's real
 * predecessors.
 */
public final class IrStructure {
    private static final Pattern LABEL = Pattern.compile("^([A-Za-z0-9_.]+):$");
    private static final Pattern TARGET = Pattern.compile("label %([A-Za-z0-9_.]+)");
    private static final Pattern INCOMING = Pattern.compile("\\[ [^,]+, %([A-Za-z0-9_.]+) \\]");

    private IrStructure() {}

    public static void assertWellFormed(String ir) {
        for (String function : ir.split("\ndefine ")) {
            if (!function.contains("{\n")) continue;
            String body = function.substring(function.indexOf("{\n") + 2, function.lastIndexOf("\n}"));
            checkFunction(function.substring(0, function.indexOf('(')), body);
        }
    }

    private static void checkFunction(String name, String body) {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : body.split("\n")) {
            Matcher label = LABEL.matcher(line);
            if (label.matches()) {
                current = new ArrayList<>();
                blocks.put(label.group(1), current);
            } else if (current != null && !line.isBlank()) {
                current.add(line.strip());
            }
        }
        Map<String, Set<String>> predecessors = new LinkedHashMap<>();
        blocks.keySet().forEach(b -> predecessors.put(b, new LinkedHashSet<>()));
        for (var block : blocks.entrySet()) {
            List<String> lines = block.getValue();
            assertTrue(!lines.isEmpty(), name + ": empty block " + block.getKey());
            String last = lines.get(lines.size() - 1);
            assertTrue(last.startsWith("br ") || last.startsWith("ret") || last.equals("unreachable"),
                    name + ": block " + block.getKey() + " does not end in a terminator: " + last);
            Matcher target = TARGET.matcher(last);
            while (target.find()) {
                assertTrue(predecessors.containsKey(target.group(1)), name + ": branch to unknown " + target.group(1));
                predecessors.get(target.group(1)).add(block.getKey());
            }
        }
        for (var block : blocks.entrySet()) {
            for (String line : block.getValue()) {
                if (!line.contains(" = phi ")) continue;
                Set<String> incoming = new LinkedHashSet<>();
                Matcher m = INCOMING.matcher(line);
                while (m.find()) incoming.add(m.group(1));
                assertEquals(predecessors.get(block.getKey()), incoming,
                        name + ": phi in " + block.getKey() + " does not match its predecessors: " + line);
            }
        }
    }
}
