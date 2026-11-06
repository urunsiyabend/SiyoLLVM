package siyollvm.toolchain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The clang that turns SiyoLLVM's IR into an executable.
 *
 * <p>Lookup order: the {@code --clang} option, then {@code $SIYOLLVM_CLANG}
 * (a path, or a command name searched on {@code PATH}), then {@code clang} on
 * {@code PATH}. clang 15 or newer is required: the IR uses opaque pointers.
 */
public final class Clang {
    public static final int MINIMUM_MAJOR_VERSION = 15;
    public static final String ENV_VARIABLE = "SIYOLLVM_CLANG";

    private static final Pattern VERSION = Pattern.compile("clang version (\\d+)\\.");
    /** Output kept from a clang run for error messages; the rest is read and dropped. */
    private static final int OUTPUT_LIMIT = 1 << 20;

    private final Path _executable;
    private final int _majorVersion;
    private final Timeouts _timeouts;

    private Clang(Path executable, int majorVersion, Timeouts timeouts) {
        _executable = executable;
        _majorVersion = majorVersion;
        _timeouts = timeouts;
    }

    /**
     * @param explicit The {@code --clang} option, if given.
     * @param env The environment to read {@code SIYOLLVM_CLANG} and {@code PATH} from.
     */
    /**
     * How long {@code clang --version} and a compile may run before clang and
     * every process it started are killed.
     */
    public record Timeouts(Duration version, Duration compile) {
        public static final Timeouts DEFAULT = new Timeouts(Duration.ofSeconds(30), Duration.ofSeconds(600));
    }

    /** {@link #locate(Optional, Map, Timeouts)} with {@link Timeouts#DEFAULT}. */
    public static Clang locate(Optional<Path> explicit, Map<String, String> env) throws ToolchainException {
        return locate(explicit, env, Timeouts.DEFAULT);
    }

    /**
     * @param explicit The {@code --clang} option, if given.
     * @param env The environment to read {@code SIYOLLVM_CLANG} and {@code PATH} from.
     * @param timeouts Bounds on the clang processes this instance starts.
     */
    public static Clang locate(Optional<Path> explicit, Map<String, String> env, Timeouts timeouts)
            throws ToolchainException {
        Path executable;
        if (explicit.isPresent()) {
            executable = explicit.get();
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw new ToolchainException("clang given with --clang is not an executable file: " + executable);
            }
        } else if (env.get(ENV_VARIABLE) != null && !env.get(ENV_VARIABLE).isBlank()) {
            String value = env.get(ENV_VARIABLE);
            executable = value.contains(File.separator) ? Path.of(value) : onPath(value, env);
            if (executable == null || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw new ToolchainException(ENV_VARIABLE + "=" + value + " does not name an executable clang");
            }
        } else {
            executable = onPath("clang", env);
            if (executable == null) {
                throw new ToolchainException("clang not found. SiyoLLVM looks for it in this order: the --clang <path>"
                        + " option, the " + ENV_VARIABLE + " environment variable, then 'clang' on PATH."
                        + " clang " + MINIMUM_MAJOR_VERSION + " or newer is required.");
            }
        }
        return new Clang(executable, majorVersionOf(executable, timeouts.version()), timeouts);
    }

    public Path executable() {
        return _executable;
    }

    public int majorVersion() {
        return _majorVersion;
    }

    /**
     * Compiles textual IR at {@code ll} to an executable at {@code exe}. The IR
     * verifier is switched on: release builds of clang skip it, and malformed
     * IR would then crash clang or be miscompiled instead of being reported.
     */
    public void compile(Path ll, Path exe) throws ToolchainException {
        Result result = run(List.of(_executable.toString(), "-O1", "-fverify-intermediate-code", "-w", "-x", "ir", ll.toString(), "-pthread", "-o", exe.toString()),
                _timeouts.compile());
        if (result.exitCode() != 0) {
            throw new ToolchainException("clang failed (exit " + result.exitCode() + "):\n" + result.output().strip());
        }
    }

    private static int majorVersionOf(Path executable, Duration timeout) throws ToolchainException {
        Result result = run(List.of(executable.toString(), "--version"), timeout);
        Matcher matcher = VERSION.matcher(result.output());
        if (result.exitCode() != 0 || !matcher.find()) {
            throw new ToolchainException(executable + " does not look like clang ('--version' printed: "
                    + result.output().strip().lines().findFirst().orElse("") + ")");
        }
        int major = Integer.parseInt(matcher.group(1));
        if (major < MINIMUM_MAJOR_VERSION) {
            throw new ToolchainException(executable + " is clang " + major + "; SiyoLLVM needs clang "
                    + MINIMUM_MAJOR_VERSION + " or newer");
        }
        return major;
    }

    private static Path onPath(String command, Map<String, String> env) {
        String path = env.get("PATH");
        if (path == null) return null;
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isEmpty()) continue;
            Path candidate = Path.of(directory, command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private record Result(int exitCode, String output) {}

    /**
     * Runs {@code command}, reading its merged output on a separate thread so a
     * child that never closes it cannot block the timeout. When the timeout
     * passes, the process and all its descendants are killed.
     */
    private static Result run(List<String> command, Duration timeout) throws ToolchainException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
        } catch (IOException e) {
            throw new ToolchainException("could not run " + command.get(0) + ": " + e.getMessage());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> drain(process.getInputStream(), output), "siyollvm-clang-output");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                killTree(process);
                throw new ToolchainException(command.get(0) + " did not finish within " + describe(timeout)
                        + " and was stopped");
            }
            // Output is complete once the stream closes; a leftover grandchild
            // holding it open must not stall us either.
            drain.join(Math.max(1000, Math.min(timeout.toMillis(), 5000)));
            synchronized (output) {
                return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new ToolchainException("interrupted while running " + command.get(0));
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream output) {
        byte[] buffer = new byte[8192];
        try (in) {
            for (int n; (n = in.read(buffer)) >= 0; ) {
                synchronized (output) {
                    int keep = Math.min(n, OUTPUT_LIMIT - output.size());
                    if (keep > 0) output.write(buffer, 0, keep);
                }
            }
        } catch (IOException ignored) {
            // The process was killed or closed its output; what was read is kept.
        }
    }

    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        try {
            process.onExit().get(5, TimeUnit.SECONDS);
            for (ProcessHandle child : descendants) child.onExit().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best effort: SIGKILL has been sent to every process we could see.
        }
    }

    private static String describe(Duration timeout) {
        return timeout.toMillis() % 1000 == 0 ? timeout.toSeconds() + " s" : timeout.toMillis() + " ms";
    }
}
