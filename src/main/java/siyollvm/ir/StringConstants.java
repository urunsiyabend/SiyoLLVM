package siyollvm.ir;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pools Siyo string literals as constant {@code SiyoStr} headers (see
 * {@code siyo_rt.h}): {@code @siyo.str.N = { i32 1, i32 <UTF-16 length>, i64 <byte length>,
 * ptr @siyo.str.N.bytes, ptr null }}, where {@code @siyo.str.N.bytes} is a constant
 * {@code [n x i8]} and the null buffer marks a literal.
 */
final class StringConstants {
    /** {@code SIYO_TAG_STR} in {@code siyo_rt.h}. */
    static final int STRING_TAG = 1;

    private final Map<String, String> _names = new LinkedHashMap<>();

    /** @return The global holding {@code value}, created on first use. */
    String nameOf(String value) {
        return _names.computeIfAbsent(value, v -> "@siyo.str." + _names.size());
    }

    String definitions() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : _names.entrySet()) {
            String name = entry.getValue();
            byte[] bytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            out.append(name).append(".bytes = private unnamed_addr constant [").append(bytes.length)
                    .append(" x i8] c\"").append(escape(bytes)).append("\"\n");
            out.append(name).append(" = private unnamed_addr constant { i32, i32, i64, ptr, ptr } { i32 ")
                    .append(STRING_TAG).append(", i32 ").append(entry.getKey().length())
                    .append(", i64 ").append(bytes.length).append(", ptr ").append(name)
                    .append(".bytes, ptr null }, align 8\n");
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
