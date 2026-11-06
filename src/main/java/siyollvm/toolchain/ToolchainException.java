package siyollvm.toolchain;

/** clang could not be found, is unsuitable, or failed. The message is meant for the user. */
public class ToolchainException extends Exception {
    public ToolchainException(String message) {
        super(message);
    }
}
