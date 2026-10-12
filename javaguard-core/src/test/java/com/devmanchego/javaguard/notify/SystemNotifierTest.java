package com.devmanchego.javaguard.notify;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Escalation;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.notify.NotificationCommands.Os;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Plan Phase 15: channels fire per configuration, the ntfy request has the documented shape, and nothing ever propagates. */
class SystemNotifierTest {

    private static final Notification HALT = new Notification(Notification.Kind.HALT, "JavaGuard halted claude",
            "JavaGuard halted claude in shop: JAVAC Compilation error. Handoff: .javaguard/handoff/H.md");
    private static final ProcessResult OK = new ProcessResult(OptionalInt.of(0), "", false, false, Duration.ZERO);

    @TempDir
    Path dir;

    private static JavaGuardConfig.Notify settings(boolean sound, boolean toast, boolean consultation, boolean ntfy, String url) {
        return new JavaGuardConfig.Notify(true, sound, toast, consultation, new JavaGuardConfig.Ntfy(ntfy, url));
    }

    private SystemNotifier notifier(JavaGuardConfig.Notify settings, ProcessRunner runner, Os os, Duration cap) {
        return new SystemNotifier(settings, runner, dir, os, name -> true, HttpClient.newHttpClient(), cap);
    }

    @Test
    void haltPlaysTheSoundAndShowsTheToastWithTheMessage() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(any(), any(), any(), any())).thenReturn(OK);

        notifier(settings(true, true, false, false, ""), runner, Os.WINDOWS, Duration.ofSeconds(2)).notify(HALT);

        verify(runner, times(2)).run(any(), any(), any(), any());
        verify(runner).run(any(List.class), any(Path.class),
                org.mockito.ArgumentMatchers.argThat(env -> HALT.message().equals(((Map<?, ?>) env).get("JAVAGUARD_NOTIFY_BODY"))),
                any(Duration.class));
    }

    @Test
    void aStuckCommandIsKilledByItsOwnTimeoutBeforeTheNotificationGivesUp() throws Exception {
        // Found on Linux with no sound server: a stuck paplay/notify-send outlived the hook because the command and the
        // notification shared the same two-second limit and the JVM exited first.
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(any(), any(), any(), any())).thenReturn(OK);
        org.mockito.ArgumentCaptor<Duration> timeout = org.mockito.ArgumentCaptor.forClass(Duration.class);

        notifier(settings(true, false, false, false, ""), runner, Os.LINUX, Duration.ofSeconds(2)).notify(HALT);

        verify(runner).run(any(), any(), any(), timeout.capture());
        assertThat(timeout.getValue()).isLessThan(Duration.ofSeconds(2)).isGreaterThan(Duration.ofSeconds(1));
    }

    @Test
    void disabledChannelsStaySilent() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);

        notifier(settings(false, false, false, false, ""), runner, Os.LINUX, Duration.ofSeconds(2)).notify(HALT);

        verify(runner, never()).run(any(), any(), any(), any());
    }

    @Test
    void consultationNotificationsNeedTheirOwnSwitch() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(any(), any(), any(), any())).thenReturn(OK);
        Notification started = Notification.consultationStarted("gemini", "shop");

        notifier(settings(true, true, false, false, ""), runner, Os.LINUX, Duration.ofSeconds(2)).notify(started);
        verify(runner, never()).run(any(), any(), any(), any());

        notifier(settings(true, true, true, false, ""), runner, Os.LINUX, Duration.ofSeconds(2)).notify(started);
        verify(runner, times(2)).run(any(), any(), any(), any());
    }

    @Test
    void failuresNeverPropagate() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(any(), any(), any(), any())).thenThrow(new IOException("no powershell"));
        notifier(settings(true, true, false, false, ""), runner, Os.WINDOWS, Duration.ofSeconds(2)).notify(HALT);

        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(runner).run(any(), any(), any(), any());
        notifier(settings(true, true, false, false, ""), runner, Os.WINDOWS, Duration.ofSeconds(2)).notify(HALT);
        // Reaching here is the assertion.
    }

    @Test
    void aChannelThatHangsIsAbandonedAtTheCap() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return OK;
        });
        long start = System.nanoTime();

        notifier(settings(true, true, false, false, ""), runner, Os.WINDOWS, Duration.ofMillis(300)).notify(HALT);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3_000);
    }

    @Test
    void ntfyReceivesAPostWithTheMessageAsBodyAndTheDocumentedHeaders() throws Exception {
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/topic", exchange -> {
            seen.add(exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("Title") + "|"
                    + exchange.getRequestHeaders().getFirst("Priority") + "|" + exchange.getRequestHeaders().getFirst("Tags") + "|"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/topic";
            notifier(settings(false, false, false, true, url), mock(ProcessRunner.class), Os.LINUX, Duration.ofSeconds(2)).notify(HALT);

            assertThat(seen.poll(5, TimeUnit.SECONDS)).isEqualTo("POST|JavaGuard halted claude|high|warning|" + HALT.message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableNtfyServerIsHarmless() {
        notifier(settings(false, false, false, true, "http://127.0.0.1:1/topic"), mock(ProcessRunner.class), Os.LINUX,
                Duration.ofSeconds(2)).notify(HALT);
    }

    @Test
    void ntfyIsSkippedWithoutAUrl() {
        SystemNotifier notifier = notifier(settings(false, false, false, true, " "), mock(ProcessRunner.class), Os.LINUX, Duration.ofSeconds(2));

        assertThat(notifier.plannedChannels(HALT)).isEmpty();
    }

    @Test
    void theHaltMessageNamesAgentProjectProblemAndHandoff() {
        Finding finding = new Finding("JG-SEC-001", "SQL built by concatenation", Category.SECURITY, Severity.CRITICAL, "ast",
                Optional.of(Path.of("src/App.java")), 5, 1, 5, "m", "r", Optional.empty(), Map.of(), new Fingerprint("f"));
        GateResult result = new GateResult(GateKind.POST_EDIT, Verdict.HALT, List.of(finding), List.of(), List.of(),
                Optional.of(new Escalation(3, "repeat", 6, "", ".javaguard/handoff/HANDOFF-s-1.md")), Optional.empty(), List.of(),
                Duration.ZERO);

        assertThat(Notification.halt("claude", "shop", result).message()).isEqualTo(
                "JavaGuard halted claude in shop: JG-SEC-001 SQL built by concatenation in src/App.java. "
                        + "Handoff: .javaguard/handoff/HANDOFF-s-1.md");
    }
}
