package siyollvm.ir;

import codeanalysis.SiyoArray;
import codeanalysis.SiyoStruct;

/**
 * LLVM types of Siyo's value types. An {@code any} ({@code Object}) is a tagged
 * heap pointer or null, and so are an array and a struct.
 */
final class LlvmTypes {
    private LlvmTypes() {}

    static String of(Class<?> type) {
        if (type == Integer.class) return "i32";
        if (type == Long.class) return "i64";
        if (type == Boolean.class) return "i1";
        if (type == String.class || type == Object.class || type == SiyoArray.class
                || type == SiyoStruct.class) return "ptr";
        throw new IllegalStateException("SiyoLLVM has no LLVM type for " + type);
    }

    /** The JVM default of the type: what an unassigned field or a fallen-off return holds. */
    static String zero(Class<?> type) {
        if (type == Boolean.class) return "false";
        if (type == String.class || type == Object.class || type == SiyoArray.class
                || type == SiyoStruct.class) return "null";
        of(type);
        return "0";
    }
}
