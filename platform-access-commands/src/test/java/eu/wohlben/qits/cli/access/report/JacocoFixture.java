package eu.wohlben.qits.cli.access.report;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A two-module maven tree with real JaCoCo output, made in the test: the sources are compiled into
 * each module's {@code target/classes}, {@code fx.Ledger} is instrumented with {@code org.jacoco.core}
 * (the same library, so the same exec format, as the agent the archetypes run) and called, and what it
 * recorded is written to {@code .qits-reports/jacoco.exec}.
 * <p>
 * The lines, as JaCoCo sees them:
 * <ul>
 *   <li>{@code service/src/main/java/fx/Ledger.java}: 3 (the constructor), 7 (the {@code if}, one
 *       branch of two: partly covered), 10, 11 and 14 covered; 8 (the throw) and 18 ({@code never})
 *       not. 5 of 7.</li>
 *   <li>{@code domain/src/main/java/fx/domain/Clock.java}: never loaded, so 3 and 5 are not covered.
 *       0 of 2.</li>
 *   <li>{@code fx.Generated}, compiled into service's classes from
 *       {@code target/generated-sources}: no source under {@code src/main/java}, so left out.</li>
 * </ul>
 */
final class JacocoFixture {

    static final String LEDGER = "service/src/main/java/fx/Ledger.java";
    static final String CLOCK = "domain/src/main/java/fx/domain/Clock.java";

    static final String LEDGER_SOURCE = """
            package fx;

            public class Ledger {
                private long balance;

                public void add(long amount) {
                    if (amount == 0) {
                        throw new IllegalArgumentException("nothing");
                    }
                    balance += amount;
                }

                public long balance() {
                    return balance;
                }

                public String never() {
                    return "never";
                }
            }
            """;

    static final String CLOCK_SOURCE = """
            package fx.domain;

            public final class Clock {
                public static int tick(int s) {
                    return s + 1;
                }
            }
            """;

    static final String GENERATED_SOURCE = """
            package fx;

            public final class Generated {
                public static int answer() {
                    return 42;
                }
            }
            """;

    private JacocoFixture() {
    }

    /** The tree under {@code root}, with its exec file. */
    static Path build(Path root) throws Exception {
        Path ledger = write(root, LEDGER, LEDGER_SOURCE);
        Path clock = write(root, CLOCK, CLOCK_SOURCE);
        Path generated = write(root, "service/target/generated-sources/annotations/fx/Generated.java",
                GENERATED_SOURCE);
        compile(root.resolve("service/target/classes"), ledger, generated);
        compile(root.resolve("domain/target/classes"), clock);
        writeExec(root.resolve(".qits-reports/jacoco.exec"),
                Files.readAllBytes(root.resolve("service/target/classes/fx/Ledger.class")));
        return root;
    }

    private static Path write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private static void compile(Path into, Path... sources) throws IOException {
        Files.createDirectories(into);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> arguments = new ArrayList<>(List.of("-g", "-d", into.toString()));
        for (Path source : sources) {
            arguments.add(source.toString());
        }
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        int exit = javac.run(null, said, said, arguments.toArray(String[]::new));
        if (exit != 0) {
            throw new IllegalStateException("javac: " + said);
        }
    }

    /** Runs the instrumented Ledger (add(2), balance()) and writes what the probes recorded. */
    private static void writeExec(Path exec, byte[] ledgerClass) throws Exception {
        IRuntime runtime = new LoggerRuntime();
        byte[] instrumented = new Instrumenter(runtime).instrument(ledgerClass, "fx.Ledger");
        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        try {
            ClassLoader loader = new ClassLoader(JacocoFixture.class.getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (name.equals("fx.Ledger")) {
                        return defineClass(name, instrumented, 0, instrumented.length);
                    }
                    throw new ClassNotFoundException(name);
                }
            };
            Class<?> type = loader.loadClass("fx.Ledger");
            Object ledger = type.getConstructor().newInstance();
            type.getMethod("add", long.class).invoke(ledger, 2L);
            type.getMethod("balance").invoke(ledger);
            ExecutionDataStore executions = new ExecutionDataStore();
            SessionInfoStore sessions = new SessionInfoStore();
            data.collect(executions, sessions, false);
            Files.createDirectories(exec.getParent());
            try (OutputStream out = Files.newOutputStream(exec)) {
                ExecutionDataWriter writer = new ExecutionDataWriter(out);
                sessions.accept(writer);
                executions.accept(writer);
            }
        } finally {
            runtime.shutdown();
        }
    }
}
