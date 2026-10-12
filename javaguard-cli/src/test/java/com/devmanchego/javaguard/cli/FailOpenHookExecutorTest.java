package com.devmanchego.javaguard.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class FailOpenHookExecutorTest {

    @Test
    void returnsTheBodysResultOnSuccess() {
        Logger logger = Logger.getLogger("javaguard.test.success");

        String result = FailOpenHookExecutor.execute(logger, () -> "rendered", () -> "fallback");

        assertThat(result).isEqualTo("rendered");
    }

    @Test
    void swallowsAnyThrowableAndReturnsTheFallback() {
        Logger logger = Logger.getLogger("javaguard.test.failure");
        Callable<String> body = () -> { throw new RuntimeException("simulated internal failure"); };

        assertThat(FailOpenHookExecutor.execute(logger, body, () -> "fallback")).isEqualTo("fallback");
    }

    @Test
    void swallowsErrorsToo() {
        Logger logger = Logger.getLogger("javaguard.test.error");
        Callable<String> body = () -> { throw new StackOverflowError(); };

        assertThat(FailOpenHookExecutor.execute(logger, body, () -> "fallback")).isEqualTo("fallback");
    }

    @Test
    void logsTheThrowableAtSevere() {
        Logger logger = Logger.getLogger("javaguard.test.logging");
        logger.setUseParentHandlers(false);
        List<LogRecord> captured = new ArrayList<>();
        Handler capturingHandler = new Handler() {
            @Override public void publish(LogRecord record) { captured.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(capturingHandler);

        try {
            FailOpenHookExecutor.execute(logger, () -> { throw new IllegalStateException("boom"); }, () -> "x");
        } finally {
            logger.removeHandler(capturingHandler);
        }

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getLevel()).isEqualTo(Level.SEVERE);
        assertThat(captured.get(0).getThrown()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNullResultFromTheBodyMeansFallback() {
        Logger logger = Logger.getLogger("javaguard.test.null-result");

        assertThat(FailOpenHookExecutor.execute(logger, () -> null, () -> "fallback")).isEqualTo("fallback");
    }
}
