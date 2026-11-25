package siyollvm.ir;

import codeanalysis.FunctionSymbol;
import siyollvm.frontend.Frontend;
import siyollvm.frontend.LoweredProgram;

import java.util.Map;
import java.util.Set;

/**
 * The standard-library functions SiyoLLVM implements natively, and those it
 * ships only as stubs.
 *
 * <p>SiyoLLVM ships {@code std/io}, {@code std/os} and {@code std/strings}
 * ({@code src/main/resources/std/}) with SiyoCompiler 0.9.0's public
 * signatures. {@code std/strings} is plain Siyo, compiled like any module.
 * The bodies in {@code std/io} and {@code std/os}, and {@code strings.builder},
 * are stubs: a call to one of them is a call into the C runtime when it is
 * listed in {@link #RUNTIME}, and rejected by the validator when it is not.
 *
 * <p>Entries are keyed by the module's identity (see
 * {@link Frontend#moduleIdentity}), the function's name and its arity, so a
 * program's own {@code std/io.siyo} (found beside it, or a dev-mode file whose
 * text differs from SiyoLLVM's) is never mistaken for SiyoLLVM's.
 */
public final class Intrinsics {
    private Intrinsics() {}

    private static final String IO = Frontend.CLASSPATH + "std/io.siyo";
    private static final String OS = Frontend.CLASSPATH + "std/os.siyo";
    private static final String STRINGS = Frontend.CLASSPATH + "std/strings.siyo";

    /** Stub functions implemented by the runtime, by {@link #key}, to the runtime function. */
    static final Map<String, String> RUNTIME = Map.ofEntries(
            Map.entry(key(IO, "readFile", 1), "@siyo_rt_io_read_file"),
            Map.entry(key(IO, "readLines", 1), "@siyo_rt_io_read_lines"),
            Map.entry(key(IO, "writeFile", 2), "@siyo_rt_io_write_file"),
            Map.entry(key(IO, "appendFile", 2), "@siyo_rt_io_append_file"),
            Map.entry(key(IO, "exists", 1), "@siyo_rt_io_exists"),
            Map.entry(key(IO, "isFile", 1), "@siyo_rt_io_is_file"),
            Map.entry(key(IO, "isDir", 1), "@siyo_rt_io_is_dir"),
            Map.entry(key(IO, "eprintln", 1), "@siyo_rt_io_eprintln"),
            Map.entry(key(IO, "mkdir", 1), "@siyo_rt_io_mkdir"),
            Map.entry(key(IO, "delete", 1), "@siyo_rt_io_delete"),
            Map.entry(key(IO, "listDir", 1), "@siyo_rt_io_list_dir"),
            Map.entry(key(OS, "args", 0), "@siyo_rt_os_args"),
            Map.entry(key(OS, "exit", 1), "@siyo_rt_os_exit"),
            Map.entry(key(OS, "env", 1), "@siyo_rt_os_env"),
            Map.entry(key(OS, "cwd", 0), "@siyo_rt_os_cwd"));

    /** Every stub function SiyoLLVM ships: those in {@link #RUNTIME} and these, which nothing implements. */
    private static final Set<String> UNIMPLEMENTED = Set.of(
            key(IO, "walk", 1), key(IO, "readBytes", 1), key(IO, "writeBytes", 2), key(IO, "copyFile", 2),
            key(STRINGS, "builder", 0));

    static String key(String identity, String name, int arity) {
        return identity + "#" + name + "/" + arity;
    }

    private static String key(LoweredProgram program, FunctionSymbol target) {
        LoweredProgram.Unit unit = program.unitOf(target);
        if (unit == null) return null;
        return key(unit.identity(), target.getName(), target.getParameters().size());
    }

    /** The runtime function a call to {@code target} (a unit's own function) runs, or null for an ordinary call. */
    public static String runtime(LoweredProgram program, FunctionSymbol target) {
        String key = key(program, target);
        return key == null ? null : RUNTIME.get(key);
    }

    /** Whether {@code target}'s body is a stub of a shipped std module, never compiled. */
    public static boolean isStub(LoweredProgram program, FunctionSymbol target) {
        String key = key(program, target);
        return key != null && (RUNTIME.containsKey(key) || UNIMPLEMENTED.contains(key));
    }

    /** Whether a call to {@code target} cannot be compiled: a stub nothing implements. */
    public static boolean isUnimplemented(LoweredProgram program, FunctionSymbol target) {
        String key = key(program, target);
        return key != null && UNIMPLEMENTED.contains(key);
    }

    /** {@code std/io.walk}: how a stub is named in diagnostics. */
    public static String displayName(LoweredProgram program, FunctionSymbol target) {
        String identity = program.unitOf(target).identity();
        String module = identity.substring(Frontend.CLASSPATH.length()).replaceFirst("\\.siyo$", "");
        return module + "." + target.getName();
    }
}
