package com.devmanchego.javaguard.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Shrinks a Java stack trace to what helps an agent (specification &sect;10.4): the exception line,
 * the frames in project code, the frame of the failing test method, and every {@code Caused by:}
 * header; the rest collapses into {@code ... N framework frames omitted}. At most
 * {@value #MAX_FRAMES} frames are kept in total.
 */
public final class StackTraceTrimmer {

    public static final int MAX_FRAMES = 15;

    private StackTraceTrimmer() {
    }

    /** Predicate over fully qualified class names: does the class belong to the project? */
    public static Predicate<String> projectClasses(Collection<Path> sourceRoots) {
        Set<String> packages = new HashSet<>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(p -> {
                    Path dir = root.relativize(p.getParent() == null ? root : p.getParent());
                    packages.add(dir.toString().replace('\\', '/').replace('/', '.'));
                });
            } catch (IOException e) {
                // An unreadable source root only makes the trimming less precise.
            }
        }
        return className -> {
            int dot = className.lastIndexOf('.');
            return packages.contains(dot < 0 ? "" : className.substring(0, dot));
        };
    }

    /**
     * @param testClass  class of the failing test, whose method frame is always kept
     * @param testMethod name of the failing test method
     */
    public static String trim(String stackTrace, Predicate<String> isProjectClass, String testClass, String testMethod) {
        List<String> lines = stackTrace.lines().toList();
        List<String> out = new ArrayList<>();
        int kept = 0;
        int omitted = 0;
        boolean sawHeader = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean frame = line.startsWith("at ") && line.contains("(");
            if (frame) {
                String owner = frameClass(line);
                String method = frameMethod(line);
                boolean wanted = isProjectClass.test(owner) || (owner.equals(testClass) && method.equals(testMethod));
                if (wanted && kept < MAX_FRAMES) {
                    omitted = flush(out, omitted);
                    out.add("  " + line);
                    kept++;
                } else {
                    omitted++;
                }
                continue;
            }
            if (line.startsWith("... ") && line.endsWith(" more")) {
                continue; // The JVM's own elision marker; our count replaces it.
            }
            boolean header = !sawHeader || line.startsWith("Caused by:") || line.startsWith("Suppressed:");
            if (header) {
                omitted = flush(out, omitted);
                out.add(line);
                sawHeader = true;
            }
            // Other non-frame lines are continuation of a multi-line exception message: dropped.
        }
        flush(out, omitted);
        return String.join("\n", out);
    }

    private static int flush(List<String> out, int omitted) {
        if (omitted > 0) {
            out.add("  ... " + omitted + " framework frame" + (omitted == 1 ? "" : "s") + " omitted");
        }
        return 0;
    }

    /** {@code at com.acme.Foo.bar(Foo.java:9)} to {@code com.acme.Foo}. */
    private static String frameClass(String line) {
        String call = frameCall(line);
        int lastDot = call.lastIndexOf('.');
        return lastDot < 0 ? call : call.substring(0, lastDot);
    }

    private static String frameMethod(String line) {
        String call = frameCall(line);
        int lastDot = call.lastIndexOf('.');
        return lastDot < 0 ? call : call.substring(lastDot + 1);
    }

    /** The {@code Class.method} part, without a Java 9+ module prefix ({@code app//}) or the location. */
    private static String frameCall(String line) {
        String call = line.substring("at ".length(), line.indexOf('('));
        int slash = call.lastIndexOf('/');
        return slash < 0 ? call : call.substring(slash + 1);
    }
}
