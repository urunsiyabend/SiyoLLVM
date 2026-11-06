package siyollvm.frontend;

import codeanalysis.text.SourceText;

/**
 * A diagnostic resolved to a file position.
 *
 * @param file   File name as shown to the user.
 * @param line   1-based line, or 0 when the position is unknown.
 * @param column 1-based column (UTF-16 code units, as siyoc reports it), or 0.
 * @param message The message.
 */
public record SourceDiagnostic(String file, int line, int column, String message) {

    /** Message prefix for constructs SiyoLLVM does not compile. */
    public static final String UNSUPPORTED_PREFIX = "[llvm] ";
    public static final String UNSUPPORTED_SUFFIX = " not supported by SiyoLLVM";

    /** Resolves {@code offset} in {@code text}; an offset outside the text gives an unknown position. */
    public static SourceDiagnostic at(String file, SourceText text, int offset, String message) {
        if (text == null || offset < 0 || offset > text.length()) {
            return new SourceDiagnostic(file, 0, 0, message);
        }
        int lineIndex = text.getLineIndex(offset);
        int column = offset - text.getLines().get(lineIndex).getStart() + 1;
        return new SourceDiagnostic(file, lineIndex + 1, column, message);
    }

    /** {@code "<construct> is"}/{@code "<constructs> are"} followed by the unsupported suffix. */
    public static SourceDiagnostic unsupported(String file, SourceText text, int offset, String constructIsOrAre) {
        return at(file, text, offset, UNSUPPORTED_PREFIX + constructIsOrAre + UNSUPPORTED_SUFFIX);
    }

    /** {@code file(line, col): message}, the format siyoc uses. */
    public String format() {
        if (line <= 0) return file + ": " + message;
        return file + "(" + line + ", " + column + "): " + message;
    }

    @Override
    public String toString() {
        return format();
    }
}
