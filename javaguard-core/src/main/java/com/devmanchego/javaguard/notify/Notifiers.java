package com.devmanchego.javaguard.notify;

import com.devmanchego.javaguard.config.JavaGuardConfig;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Where the program gets its {@link Notifier}: the real one from the configuration, or - in tests, so that no run of a
 * test suite ever plays a sound or pops a toast - whatever {@link #overrideForTests} installed.
 */
public final class Notifiers {

    private static volatile Function<JavaGuardConfig.Notify, Notifier> override;

    private Notifiers() {
    }

    public static Notifier forConfig(JavaGuardConfig.Notify settings, Path projectRoot, Map<String, String> env) {
        Function<JavaGuardConfig.Notify, Notifier> replacement = override;
        if (replacement != null) {
            return replacement.apply(settings);
        }
        // The test suites run with this property so that no build ever plays a sound or pops a toast.
        return "off".equals(System.getProperty("javaguard.notifications")) ? Notifier.NONE
                : new SystemNotifier(settings, projectRoot, env);
    }

    /** {@code null} restores the real notifier. */
    public static void overrideForTests(Function<JavaGuardConfig.Notify, Notifier> replacement) {
        override = replacement;
    }
}
