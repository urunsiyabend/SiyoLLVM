package siyollvm.cli;

import siyollvm.Compiler;
import siyollvm.frontend.SourceDiagnostic;
import siyollvm.toolchain.Clang;
import siyollvm.toolchain.RuntimeSource;
import siyollvm.toolchain.ToolchainException;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The {@code siyollvm} command.
 *
 * <p>Exit status: 0 success, 1 compile/toolchain error, 2 usage or internal
 * error. Output streams are UTF-8 whatever the locale.
 */
public final class Main {
    private static final int OK = 0;
    private static final int FAILED = 1;
    private static final int USAGE = 2;

    private static final String HELP = """
            Usage: siyollvm <command> [options]

            SiyoLLVM is an experimental compiler from a subset of Siyo to LLVM IR and
            native executables. See docs/SUBSET.md for what it accepts.

            Commands:
              emit-llvm <file.siyo> [-o <out.ll>]
                  Write textual LLVM IR (stdout without -o).
              build <file.siyo> [-o <exe>] [--clang <path>] [--emit-llvm <out.ll>]
                  Compile to a native executable with clang (default output: the
                  file name without .siyo, in the current directory).
              emit-runtime <directory>
                  Write the C runtime (siyo_rt.c, siyo_rt.h) into <directory>, creating
                  it and replacing earlier copies. Link by hand with:
                  clang -O1 -x ir prog.ll -x c -std=c11 siyo_rt.c -pthread -o prog

            Options:
              -h, --help       Print this help and exit
              -v, --version    Print the version and exit

            clang (15 or newer) is looked up in this order: --clang <path>, the
            SIYOLLVM_CLANG environment variable (path or command name), 'clang' on PATH.
            """;

    private Main() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int status = run(args, out, err, System.getenv());
        out.flush();
        err.flush();
        System.exit(status);
    }

    /** Runs the command line; returns the exit status. */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
        return run(args, out, err, env, Clang.Timeouts.DEFAULT);
    }

    /** As {@link #run(String[], PrintStream, PrintStream, Map)}, with explicit clang time limits. */
    static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env, Clang.Timeouts timeouts) {
        if (args.length == 0) return usage(err, "no command given");
        switch (args[0]) {
            case "-h", "--help" -> {
                out.print(HELP);
                return OK;
            }
            case "-v", "--version" -> {
                out.println("siyollvm " + version());
                return OK;
            }
            case "emit-runtime" -> {
                return emitRuntime(args, out, err);
            }
            case "emit-llvm", "build" -> { }
            default -> {
                return usage(err, "unknown command '" + args[0] + "'");
            }
        }

        String command = args[0];
        String source = null;
        String output = null;
        String clang = null;
        String irOutput = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            boolean takesValue = arg.equals("-o")
                    || (command.equals("build") && (arg.equals("--clang") || arg.equals("--emit-llvm")));
            if (takesValue) {
                if (i + 1 >= args.length) return usage(err, arg + " needs a value");
                String value = args[++i];
                if (value.startsWith("-")) {
                    return usage(err, arg + " needs a value, but the next argument is the option '" + value
                            + "' (write ./" + value + " for a file whose name starts with '-')");
                }
                switch (arg) {
                    case "-o" -> output = value;
                    case "--clang" -> clang = value;
                    default -> irOutput = value;
                }
            } else if (arg.startsWith("-") && !arg.equals("-")) {
                return usage(err, "unknown option '" + arg + "' for " + command);
            } else if (source == null) {
                source = arg;
            } else {
                return usage(err, "only one source file is accepted");
            }
        }
        if (source == null) return usage(err, command + " needs a source file");

        try {
            Path sourcePath = Path.of(source);
            Path outputPath = output == null ? null : Path.of(output);
            Path irPath = irOutput == null ? null : Path.of(irOutput);
            if (command.equals("build") && outputPath == null) outputPath = Path.of(defaultExecutableName(sourcePath));

            // Every check on output paths happens before anything is written.
            String outputOption = command.equals("build") && output == null ? "the default executable" : "-o";
            if (outputPath != null && sameFile(outputPath, sourcePath)) {
                return refuse(err, outputOption + " " + outputPath + " would overwrite the source file " + sourcePath);
            }
            if (irPath != null && sameFile(irPath, sourcePath)) {
                return refuse(err, "--emit-llvm " + irPath + " would overwrite the source file " + sourcePath);
            }
            if (irPath != null && sameFile(irPath, outputPath)) {
                return refuse(err, outputOption + " and --emit-llvm name the same file: " + outputPath);
            }

            String text = readUtf8(sourcePath, err);
            if (text == null) return FAILED;
            Compiler.IrResult result = Compiler.toIr(sourcePath.getFileName().toString(), text);
            if (!result.diagnostics().isEmpty()) {
                for (SourceDiagnostic diagnostic : result.diagnostics()) err.println(diagnostic.format());
                return FAILED;
            }
            if (command.equals("emit-llvm")) {
                if (outputPath == null) {
                    out.print(result.ir());
                } else {
                    writeAtomically(outputPath, result.ir());
                }
                return OK;
            }
            return build(result.ir(), outputPath, clang, irPath, env, timeouts, err);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return FAILED;
        } catch (InvalidPathException e) {
            // The JVM decodes arguments with the locale's charset; under LC_ALL=C a
            // non-ASCII file name arrives already mangled.
            err.println("error: the file name '" + e.getInput() + "' cannot be represented in this locale's encoding ("
                    + System.getProperty("sun.jnu.encoding") + "); run SiyoLLVM under a UTF-8 locale,"
                    + " for example LC_ALL=C.UTF-8 (bin/siyollvm does this itself)");
            return FAILED;
        } catch (RuntimeException | StackOverflowError e) {
            err.println("internal error: " + e);
            return USAGE;
        }
    }

    private static int emitRuntime(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) return usage(err, "emit-runtime needs a directory");
        if (args.length > 2) {
            return usage(err, args[1].startsWith("-") || args[2].startsWith("-")
                    ? "unknown option for emit-runtime"
                    : "only one directory is accepted");
        }
        if (args[1].startsWith("-")) {
            return usage(err, "unknown option '" + args[1] + "' for emit-runtime (write ./" + args[1]
                    + " for a directory whose name starts with '-')");
        }
        try {
            Path directory = Path.of(args[1]);
            Path c = RuntimeSource.writeTo(directory);
            out.println("wrote " + c + " and " + c.resolveSibling(RuntimeSource.HEADER_FILE));
            return OK;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return FAILED;
        } catch (InvalidPathException e) {
            err.println("error: the directory name '" + e.getInput() + "' cannot be represented in this locale's encoding");
            return FAILED;
        }
    }

    private static int build(String ir, Path executable, String clangOption, Path irOutput,
                             Map<String, String> env, Clang.Timeouts timeouts, PrintStream err) throws IOException {
        Clang clang;
        try {
            clang = Clang.locate(Optional.ofNullable(clangOption).map(Path::of), env, timeouts);
        } catch (ToolchainException e) {
            err.println("error: " + e.getMessage());
            return FAILED;
        }
        Path temporary = null;
        // clang writes next to the target under a fresh name; only a finished
        // executable replaces the target, so a failure or timeout leaves no
        // partial file and keeps any previous executable.
        Path pending = siblingTemporary(executable);
        try {
            Path ll;
            if (irOutput != null) {
                ll = irOutput;
                writeAtomically(ll, ir);
            } else {
                temporary = Files.createTempDirectory("siyollvm");
                ll = temporary.resolve("program.ll");
                Files.writeString(ll, ir, StandardCharsets.UTF_8);
            }
            clang.compile(ll, pending);
            moveIntoPlace(pending, executable);
            return OK;
        } catch (ToolchainException e) {
            err.println("error: " + e.getMessage());
            return FAILED;
        } finally {
            Files.deleteIfExists(pending);
            if (temporary != null) deleteRecursively(temporary);
        }
    }

    private static int refuse(PrintStream err, String problem) {
        err.println("error: " + problem + "; nothing was written");
        return USAGE;
    }

    /**
     * Whether two paths name the same file: the same inode when both exist
     * (hardlinks, symlinks), else the same location once the parent
     * directory's symlinks are resolved.
     */
    private static boolean sameFile(Path a, Path b) throws IOException {
        if (a == null || b == null) return false;
        if (Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)) return true;
        return location(a).equals(location(b));
    }

    private static Path location(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) return absolute.toRealPath();
        Path parent = absolute.getParent();
        if (parent != null && Files.isDirectory(parent)) return parent.toRealPath().resolve(absolute.getFileName());
        return absolute;
    }

    private static Path siblingTemporary(Path target) {
        Path absolute = target.toAbsolutePath();
        return absolute.resolveSibling("." + absolute.getFileName() + "." + java.util.UUID.randomUUID() + ".siyollvm.tmp");
    }

    /** Writes {@code text} to a temporary file beside {@code target}, then renames it over the target. */
    private static void writeAtomically(Path target, String text) throws IOException {
        Path pending = siblingTemporary(target);
        try {
            Files.writeString(pending, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            moveIntoPlace(pending, target);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static void moveIntoPlace(Path pending, Path target) throws IOException {
        try {
            Files.move(pending, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(pending, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String defaultExecutableName(Path source) {
        String name = source.getFileName().toString();
        return name.endsWith(".siyo") ? name.substring(0, name.length() - ".siyo".length()) : name + ".out";
    }

    /** Reads the file as strict UTF-8; prints the problem and returns null when it cannot. */
    private static String readUtf8(Path file, PrintStream err) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            err.println("error: cannot read " + file + ": file not found");
            return null;
        } catch (IOException e) {
            err.println("error: cannot read " + file + ": " + e.getMessage());
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            err.println("error: " + file + " is not valid UTF-8");
            return null;
        }
    }

    private static int usage(PrintStream err, String problem) {
        err.println("error: " + problem);
        err.println("usage: siyollvm emit-llvm <file.siyo> [-o out.ll] | build <file.siyo> [-o exe] | emit-runtime <directory> (see --help)");
        return USAGE;
    }

    private static String version() {
        try (InputStream in = Main.class.getResourceAsStream("/siyollvm/version.properties")) {
            if (in == null) return "unknown";
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> all = new ArrayList<>(paths.toList());
            all.sort(Comparator.reverseOrder());
            for (Path path : all) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover temporary file is not worth failing a successful build for.
        }
    }
}
