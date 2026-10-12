package com.devmanchego.javaguard.notify;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.notify.NotificationCommands.Call;
import com.devmanchego.javaguard.notify.NotificationCommands.Os;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolLocator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code sound}, {@code toast} and {@code ntfy} channels (specification &sect;16). Channels run in parallel and
 * the whole notification is capped at two seconds: what has not finished by then is abandoned. Failures are logged,
 * never propagated. The terminal bell is not here: it travels in the agent's own output (the adapter).
 *
 * <p>HALT uses every enabled channel; consultation start and result only when {@code notify.consultation} is on.
 */
public final class SystemNotifier implements Notifier {

    /** Specification &sect;16: fire-and-forget, 2 s at most. */
    public static final Duration CAP = Duration.ofSeconds(2);

    private static final Logger LOG = Logger.getLogger(SystemNotifier.class.getName());

    private final JavaGuardConfig.Notify settings;
    private final ProcessRunner processes;
    private final Path workingDir;
    private final Os os;
    private final Predicate<String> onPath;
    private final java.util.function.Supplier<HttpClient> http;
    private final Duration cap;

    public SystemNotifier(JavaGuardConfig.Notify settings, Path workingDir, Map<String, String> env) {
        this(settings, new ProcessRunner(), workingDir, Os.current(),
                name -> BuildToolLocator.findOnPath(name, env, Os.current() == Os.WINDOWS).isPresent(),
                // Built on first use: creating an HttpClient initializes TLS, ~400 ms that a run with no ntfy never needs.
                lazyClient(), CAP);
    }

    SystemNotifier(JavaGuardConfig.Notify settings, ProcessRunner processes, Path workingDir, Os os, Predicate<String> onPath,
                   HttpClient http, Duration cap) {
        this(settings, processes, workingDir, os, onPath, (java.util.function.Supplier<HttpClient>) () -> http, cap);
    }

    private SystemNotifier(JavaGuardConfig.Notify settings, ProcessRunner processes, Path workingDir, Os os, Predicate<String> onPath,
                           java.util.function.Supplier<HttpClient> http, Duration cap) {
        this.settings = settings;
        this.processes = processes;
        this.workingDir = workingDir;
        this.os = os;
        this.onPath = onPath;
        this.http = http;
        this.cap = cap;
    }

    @Override
    public void notify(Notification notification) {
        try {
            if (notification.kind() != Notification.Kind.HALT && !settings.consultation()) {
                return;
            }
            List<Thread> channels = new ArrayList<>();
            if (settings.sound()) {
                NotificationCommands.sound(os, onPath).ifPresent(call -> channels.add(start("sound", () -> run(call))));
            }
            if (settings.toast()) {
                NotificationCommands.toast(os, onPath, notification.title(), notification.message())
                        .ifPresent(call -> channels.add(start("toast", () -> run(call))));
            }
            if (settings.ntfy().enabled() && !settings.ntfy().url().isBlank()) {
                channels.add(start("ntfy", () -> post(notification)));
            }
            long deadline = System.nanoTime() + cap.toNanos();
            for (Thread channel : channels) {
                channel.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Notification failed.", e);
        }
    }

    private static java.util.function.Supplier<HttpClient> lazyClient() {
        return new java.util.function.Supplier<>() {
            private HttpClient client;

            @Override
            public synchronized HttpClient get() {
                if (client == null) {
                    client = HttpClient.newBuilder().connectTimeout(CAP).build();
                }
                return client;
            }
        };
    }

    private static Thread start(String name, Runnable task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "The " + name + " notification failed.", e);
            }
        }, "javaguard-notify-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * A little less than the whole-notification cap: the command must be killed by its own timeout, while this notification
     * still waits for it. At exactly the cap the hook's JVM could exit first and leave a stuck {@code paplay} or
     * {@code notify-send} (no sound server, no D-Bus) running on its own.
     */
    private Duration commandTimeout() {
        Duration margin = Duration.ofMillis(400);
        return cap.compareTo(margin.multipliedBy(2)) > 0 ? cap.minus(margin) : cap.dividedBy(2);
    }

    private void run(Call call) {
        try {
            processes.run(call.command(), workingDir, call.env(), commandTimeout());
        } catch (IOException e) {
            LOG.log(Level.FINE, "Notification command failed: " + call.command().get(0), e);
        }
    }

    private void post(Notification notification) {
        Request request = ntfyRequest(settings.ntfy().url(), notification, cap);
        try {
            HttpResponse<Void> response = http.get().send(request.http(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                LOG.warning("ntfy answered HTTP " + response.statusCode());
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "ntfy request failed.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The ntfy publish request (https://docs.ntfy.sh/publish/): the message is the body, the rest are headers. */
    record Request(HttpRequest http) {
    }

    static Request ntfyRequest(String url, Notification notification, Duration timeout) {
        boolean halt = notification.kind() == Notification.Kind.HALT;
        return new Request(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Title", ascii(notification.title()))
                .header("Priority", halt ? "high" : "default")
                .header("Tags", halt ? "warning" : "speech_balloon")
                .POST(HttpRequest.BodyPublishers.ofString(notification.message(), StandardCharsets.UTF_8)).build());
    }

    /** HTTP header values must be plain ASCII. */
    private static String ascii(String text) {
        return text.replaceAll("[^\\x20-\\x7E]", "?");
    }

    /** Test hook: which channels would fire for a notification, without firing them. */
    List<String> plannedChannels(Notification notification) {
        List<String> out = new ArrayList<>();
        if (notification.kind() != Notification.Kind.HALT && !settings.consultation()) {
            return out;
        }
        Optional<Call> sound = settings.sound() ? NotificationCommands.sound(os, onPath) : Optional.empty();
        sound.ifPresent(c -> out.add("sound"));
        if (settings.toast() && NotificationCommands.toast(os, onPath, notification.title(), notification.message()).isPresent()) {
            out.add("toast");
        }
        if (settings.ntfy().enabled() && !settings.ntfy().url().isBlank()) {
            out.add("ntfy");
        }
        return out;
    }
}
