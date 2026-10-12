package com.devmanchego.javaguard.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingBootstrapTest {

    private final Logger root = Logger.getLogger("");

    @AfterEach
    void removeInstalledHandlers() {
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof FileHandler) {
                handler.close();
                root.removeHandler(handler);
            }
        }
    }

    @Test
    void configureRemovesConsoleHandlersAndInstallsAFileHandler(@TempDir Path projectRoot) {
        root.addHandler(new ConsoleHandler());

        LoggingBootstrap.configure(new JavaGuardPaths(projectRoot), false);

        assertThat(root.getHandlers()).noneMatch(h -> h instanceof ConsoleHandler);
        assertThat(root.getHandlers()).anyMatch(h -> h instanceof FileHandler);
    }

    @Test
    void silenceConsoleRemovesConsoleHandlersWithoutAddingAFile() {
        root.addHandler(new ConsoleHandler());

        LoggingBootstrap.silenceConsole();

        assertThat(root.getHandlers()).noneMatch(h -> h instanceof ConsoleHandler);
        assertThat(root.getHandlers()).noneMatch(h -> h instanceof FileHandler);
    }

    @Test
    void configureNeverWritesToStdoutOrStderr(@TempDir Path projectRoot) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
        try {
            LoggingBootstrap.configure(new JavaGuardPaths(projectRoot), true);
            Logger.getLogger(LoggingBootstrapTest.class.getName()).warning("test message");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertThat(capturedOut.toByteArray()).isEmpty();
        assertThat(capturedErr.toByteArray()).isEmpty();
    }

    @Test
    void configureCreatesTheLogFileUnderProjectState(@TempDir Path projectRoot) {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        LoggingBootstrap.configure(paths, false);
        Logger.getLogger(LoggingBootstrapTest.class.getName()).info("hello");

        assertThat(paths.logsDir()).isDirectory();
        assertThat(paths.logsDir().toFile().listFiles()).isNotEmpty();
    }

    @Test
    void configureIsIdempotentAcrossCallsInTheSameProcess(@TempDir Path projectRoot) {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        LoggingBootstrap.configure(paths, false);
        int handlersAfterFirstCall = root.getHandlers().length;
        LoggingBootstrap.configure(paths, true);

        assertThat(root.getHandlers().length).isEqualTo(handlersAfterFirstCall);
    }
}
