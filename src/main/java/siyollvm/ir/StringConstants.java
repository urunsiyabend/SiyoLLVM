package siyollvm.ir;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pools Siyo string literals as length-prefixed UTF-8 constants. */
final class StringConstants {
    private final Map<String, String> _names = new LinkedHashMap<>();

    /** @return The global holding {@code value}, created on first use. */
    String nameOf(String value) {
        return _names.computeIfAbsent(value, v -> "@siyo.str." + _names.size());
    }

    String definitions() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : _names.entrySet()) {
            byte[] bytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            String array = "[" + bytes.length + " x i8]";
            out.append(entry.getValue()).append(" = private unnamed_addr constant { i64, ").append(array)
                    .append(" } { i64 ").append(bytes.length).append(", ").append(array)
                    .append(" c\"").append(escape(bytes)).append("\" }, align 8\n");
        }
        return out.toString();
    }

    /** LLVM {@code c"..."} body: printable ASCII as is, everything else as {@code \XX}. */
    static String escape(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c >= 0x20 && c < 0x7F && c != '"' && c != '\\') {
                out.append((char) c);
            } else {
                out.append('\\').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }
}
