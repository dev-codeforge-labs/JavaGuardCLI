package com.devmanchego.javaguard.process;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds commands that relaunch the current JDK against this module's own
 * {@code target/test-classes}, to run the tiny helper mains below.
 *
 * <p>Deliberately does not reuse {@code java.class.path}: under Surefire that
 * property can point at a single manifest-only boot jar rather than the full
 * classpath. The helpers below depend on nothing but {@code java.base}, so
 * the compiled test-classes directory alone is enough to run them.
 */
final class TestJvm {

    private TestJvm() {
    }

    static List<String> command(Class<?> helperMain, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-cp");
        cmd.add(testClasspath());
        cmd.add(helperMain.getName());
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("Cannot determine the current java executable"));
    }

    private static String testClasspath() {
        try {
            return Path.of(TestJvm.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
