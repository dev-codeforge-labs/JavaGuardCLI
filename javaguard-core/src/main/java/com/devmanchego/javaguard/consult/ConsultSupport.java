package com.devmanchego.javaguard.consult;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.project.BuildToolLocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which second model to ask (specification &sect;15): the first entry of {@code consult.order} that is not the agent
 * being helped and whose executable can be found. Consultation is opt-in ({@code consult.enabled}).
 */
public final class ConsultSupport {

    /** The chosen consultant and its ready-to-run command (executable resolved to an absolute path). */
    public record Plan(AgentKind agent, List<String> command) {
        public Plan {
            command = List.copyOf(command);
        }
    }

    private final JavaGuardConfig.Consult settings;
    private final Map<String, String> env;
    private final boolean windows;

    public ConsultSupport(JavaGuardConfig.Consult settings, Map<String, String> env) {
        this(settings, env, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    ConsultSupport(JavaGuardConfig.Consult settings, Map<String, String> env, boolean windows) {
        this.settings = settings;
        this.env = env;
        this.windows = windows;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public Duration timeout() {
        return Duration.ofSeconds(settings.timeoutSeconds());
    }

    /** The consultant for a session driven by {@code currentAgent}, empty when disabled or none is available. */
    public Optional<Plan> select(String currentAgent) {
        if (!settings.enabled()) {
            return Optional.empty();
        }
        for (AgentKind candidate : settings.order()) {
            if (candidate == AgentKind.MANUAL || candidate.cliName().equals(currentAgent)) {
                continue;
            }
            Optional<Plan> plan = planFor(candidate);
            if (plan.isPresent()) {
                return plan;
            }
        }
        return Optional.empty();
    }

    /** The command for one specific consultant, empty when its executable is not found. */
    public Optional<Plan> planFor(AgentKind agent) {
        List<String> template = ConsultCommands.template(agent, settings.commands());
        if (template.isEmpty()) {
            return Optional.empty();
        }
        List<String> command = new ArrayList<>(ConsultCommands.render(template, settings.claude().model()));
        return resolve(command.get(0)).map(exe -> {
            command.set(0, exe.toString());
            return new Plan(agent, command);
        });
    }

    /** Availability report for {@code doctor}: every agent in the order with where it was found. */
    public Map<AgentKind, Optional<Path>> availability() {
        Map<AgentKind, Optional<Path>> out = new java.util.LinkedHashMap<>();
        for (AgentKind agent : settings.order()) {
            List<String> template = ConsultCommands.template(agent, settings.commands());
            out.put(agent, template.isEmpty() ? Optional.empty() : resolve(template.get(0)));
        }
        return out;
    }

    private Optional<Path> resolve(String executable) {
        Path direct = Path.of(executable);
        if (direct.isAbsolute() || executable.contains("/") || executable.contains("\\")) {
            return Files.isRegularFile(direct) ? Optional.of(direct.toAbsolutePath().normalize()) : Optional.empty();
        }
        return BuildToolLocator.findOnPath(executable, env, windows);
    }
}
