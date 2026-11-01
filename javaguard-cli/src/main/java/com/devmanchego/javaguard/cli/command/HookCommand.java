package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.adapter.AgentAdapter;
import com.devmanchego.javaguard.adapter.AgentAdapters;
import com.devmanchego.javaguard.adapter.AgentDetector;
import com.devmanchego.javaguard.adapter.AgentResponse;
import com.devmanchego.javaguard.adapter.ParseContext;
import com.devmanchego.javaguard.adapter.PathNormalizer;
import com.devmanchego.javaguard.adapter.PayloadException;
import com.devmanchego.javaguard.adapter.PayloadFields;
import com.devmanchego.javaguard.adapter.PayloadReader;
import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.change.ProcessGitClient;
import com.devmanchego.javaguard.cli.FailOpenHookExecutor;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.config.LoadedConfig;
import com.devmanchego.javaguard.gate.ConfigGuard;
import com.devmanchego.javaguard.gate.EndOfTurnGate;
import com.devmanchego.javaguard.gate.EndOfTurnState;
import com.devmanchego.javaguard.gate.GitWorkspace;
import com.devmanchego.javaguard.gate.TurnBaseline;
import com.devmanchego.javaguard.gate.PostEditGate;
import com.devmanchego.javaguard.model.AgentFeedback;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.notify.Notification;
import com.devmanchego.javaguard.notify.Notifiers;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.report.AgentReportRenderer;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.devmanchego.javaguard.state.LoggingBootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hook entry point (specification &sect;4, contracts &sect;1-7).
 *
 * <p>Flow: read the payload from stdin; resolve the project root; honor the
 * recursion guard; configure file logging; identify the agent; parse the
 * payload with its adapter; load configuration; run the gate; write the
 * adapter's response. Every failure is fail-open: exit 0, with a user message
 * when the agent is known, silently otherwise.
 *
 * <p><strong>Gate scope.</strong> The POST_EDIT gate runs for Claude Code (Phase 7);
 * its wiring for Gemini CLI and Codex arrives with their end-to-end validation
 * (Phase 16), and END_OF_TURN with Phases 9 and 16. Until then those combinations
 * only track changes and answer PASS. Everything around the gates (agent I/O,
 * configuration, fail-open) is final.
 */
@Command(mixinStandardHelpOptions = true, name = "hook",
        description = "Hook entry point invoked by the AI coding CLI (reads the payload from stdin).")
public final class HookCommand implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(HookCommand.class.getName());

    @ParentCommand
    private Main parent;

    @Option(names = "--agent",
            description = "Valid values: claude, gemini, codex. Default: detected from the payload (contracts section 6).")
    private String agent;

    @Option(names = "--gate",
            description = "Valid values: pre-edit, post-edit, end-of-turn. Default: derived from the payload's event "
                    + "name, which always wins when present.")
    private String gate;

    @Override
    public Integer call() {
        LoggingBootstrap.silenceConsole();
        // Everything is inside the fail-open executor, the start-up too: under a POSIX locale the JVM cannot even represent
        // a working directory with accents, and that must cost the agent nothing (exit 0, no output), never exit 5.
        return FailOpenHookExecutor.execute(LOG, this::run, () -> 0);
    }

    private Integer run() {
        Map<String, String> env = ProcessEnvironment.get();
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        Optional<Path> explicitRoot = explicitRoot(env);
        if (new JavaGuardPaths(explicitRoot.orElse(userDir)).isDisabled(env)) {
            // Recursion guard / user opt-out (specification section 1.1): nothing is read or written.
            return 0;
        }

        AgentResponse response = FailOpenHookExecutor.execute(LOG, () -> handle(env, explicitRoot, userDir),
                AgentResponse::silent);
        write(System.out, response.stdout());
        write(System.err, response.stderr());
        return response.exitCode();
    }

    private AgentResponse handle(Map<String, String> env, Optional<Path> explicitRoot, Path userDir) {
        Optional<AgentKind> requestedAgent = parseOption("--agent", agent, AgentKind::fromCliName)
                .filter(a -> a != AgentKind.MANUAL);
        Optional<GateKind> requestedGate = parseOption("--gate", gate, GateKind::fromCliName);

        PayloadRead read = readPayload();
        JsonNode payload = read.payload();
        PayloadException payloadError = read.error();

        Path root = explicitRoot.or(() -> payloadCwd(payloadOrEmpty(payload), userDir)).orElse(userDir);
        JavaGuardPaths paths = new JavaGuardPaths(root);
        if (paths.isDisabled(env)) {
            return AgentResponse.silent();
        }
        paths.ensureLayout();
        LoggingBootstrap.configure(paths, parent != null && parent.verbose);

        if (payloadError != null) {
            LOG.log(Level.WARNING, "Unusable hook payload; failing open: " + payloadError.getMessage(), payloadError);
            return requestedAgent.map(a -> AgentAdapters.forKind(a).renderInternalError())
                    .orElse(AgentResponse.silent());
        }

        Optional<AgentKind> kind = requestedAgent.or(() -> AgentDetector.detect(payloadOrEmpty(payload), env));
        if (kind.isEmpty()) {
            LOG.warning(() -> "Cannot identify the agent from the payload and no --agent was given; failing open. "
                    + "hook_event_name=" + PayloadFields.text(payloadOrEmpty(payload), "hook_event_name").orElse("<none>"));
            return AgentResponse.silent();
        }
        AgentAdapter adapter = AgentAdapters.forKind(kind.get());
        try {
            HookEvent event = adapter.parse(payload, ParseContext.now(requestedGate, root));
            // What configures JavaGuard is recorded when the session is first seen, before any edit of the agent can reach it.
            ConfigGuard guard = new ConfigGuard(paths, Path.of(System.getProperty("user.home")));
            guard.ensureBaseline(event.sessionId());
            JavaGuardConfig config = null;
            if (event.gate() != GateKind.PRE_EDIT && event.gate() != GateKind.TURN_START) {
                // PRE_EDIT only snapshots (specification section 9.1, target < 300 ms): loading
                // YAML configuration would roughly double its cost for nothing it uses.
                config = loadConfig(root, env, guard, event.sessionId()).config();
            }
            LOG.info(() -> "hook " + event.agent().cliName() + " " + event.gate().cliName()
                    + " session=" + event.sessionId() + " tool=" + event.toolName()
                    + " changes=" + event.changes().size());
            if (event.gate() == GateKind.TURN_START) {
                recordTurnStart(paths, event);
                return adapter.render(event.gate(), AgentFeedback.pass());
            }
            if (event.gate() == GateKind.POST_EDIT && !event.changes().isEmpty()) {
                return adapter.render(event.gate(), runPostEditGate(paths, config, event, guard));
            }
            if (event.gate() == GateKind.END_OF_TURN) {
                return adapter.render(event.gate(), runEndOfTurnGate(paths, config, env, event, guard));
            }
            trackChanges(paths, event);
            return adapter.render(event.gate(), AgentFeedback.pass());
        } catch (Exception | LinkageError e) {
            LOG.log(Level.SEVERE, "JavaGuard internal error while handling a " + kind.get().cliName()
                    + " hook; failing open.", e);
            return adapter.renderInternalError();
        }
    }

    /**
     * A new turn begins: record which relevant files are already uncommitted, so that whatever a shell command writes
     * during the turn is told apart from earlier work at the next end of turn. Bookkeeping: a failure costs nothing.
     */
    private static void recordTurnStart(JavaGuardPaths paths, HookEvent event) {
        try {
            new TurnBaseline(paths, new GitWorkspace(new ProcessRunner(), paths.projectRoot(), GIT_TIMEOUT),
                    new EndOfTurnState.Store(paths)).record(event.sessionId());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The working tree was not recorded at the start of the turn.", e);
        }
    }

    private static AgentFeedback runPostEditGate(JavaGuardPaths paths, JavaGuardConfig config, HookEvent event,
                                                 ConfigGuard guard) {
        PostEditGate gate = new PostEditGate(paths, config, newTracker(paths), Clock.systemUTC(),
                new GitWorkspace(new ProcessRunner(), paths.projectRoot(), GIT_TIMEOUT));
        return feedback("post-edit", guard.enforce(gate.run(event), event.sessionId()), config, event, paths);
    }

    private static AgentFeedback runEndOfTurnGate(JavaGuardPaths paths, JavaGuardConfig config, Map<String, String> env,
                                                  HookEvent event, ConfigGuard guard) {
        EndOfTurnGate gate = EndOfTurnGate.standard(paths, config, env, newTracker(paths), gitClient(), GIT_TIMEOUT, Clock.systemUTC());
        return feedback("end-of-turn", guard.enforce(gate.run(event), event.sessionId()), config, event, paths);
    }

    /** The agent report for a non-PASS result; the gate's notes go to the user only, never to the model. */
    private static AgentFeedback feedback(String label, GateResult result, JavaGuardConfig config, HookEvent event,
                                          JavaGuardPaths paths) {
        LOG.info(() -> label + " gate: " + result.verdict() + " blocking=" + result.blocking().size()
                + " advisory=" + result.advisory().size() + " notes=" + result.notes().size()
                + " elapsedMs=" + result.elapsed().toMillis());
        result.notes().forEach(note -> LOG.info(label + " note: " + note));
        if (result.verdict() == Verdict.PASS) {
            AgentFeedback pass = AgentReportRenderer.render(result, config.report()); // Carries the suppression notices...
            List<String> warnings = result.notes().stream().filter(n -> n.startsWith("WARNING:")).toList();
            if (warnings.isEmpty()) {
                return pass;
            }
            List<String> notices = new ArrayList<>(pass.userNotices()); // ...and a warning that a check could not run.
            notices.addAll(warnings);
            return pass.withUserNotices(notices);
        }
        List<String> notices = new ArrayList<>();
        AgentFeedback feedback = AgentReportRenderer.render(result, config.report());
        notices.addAll(feedback.userNotices());
        notices.addAll(result.notes());
        feedback = feedback.withUserNotices(notices);
        // The terminal bell is for HALT only (specification section 16): it is the one moment the user must look.
        if (result.verdict() != Verdict.HALT) {
            return feedback;
        }
        Notifiers.forConfig(config.notifications(), paths.projectRoot(), ProcessEnvironment.get()).notify(
                Notification.halt(event.agent().cliName(), String.valueOf(paths.projectRoot().getFileName()), result));
        return feedback.withBell(config.notifications().terminalBell());
    }

    private static GitClient gitClient() {
        return new ProcessGitClient(new ProcessRunner(), GIT_TIMEOUT);
    }

    private static ChangeTracker newTracker(JavaGuardPaths paths) {
        return new ChangeTracker(paths, new ProcessGitClient(new ProcessRunner(), GIT_TIMEOUT), Clock.systemUTC());
    }

    /**
     * Baselines and ledger (specification section 9.1): PRE_EDIT snapshots, POST_EDIT
     * records. Bookkeeping must never cost the agent anything: a failure here (lock
     * timeout, unreadable file) is logged and the gate still answers normally.
     */
    private static void trackChanges(JavaGuardPaths paths, HookEvent event) {
        if (event.changes().isEmpty() || event.gate() == GateKind.END_OF_TURN) {
            return;
        }
        ChangeTracker tracker = newTracker(paths);
        try {
            if (event.gate() == GateKind.PRE_EDIT) {
                tracker.snapshotBeforeEdit(event);
            } else {
                tracker.recordAfterEdit(event);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Change tracking skipped for this " + event.gate().cliName() + " run.", e);
        }
    }

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(5);

    /** Either a parsed payload or the reason there is none. */
    private record PayloadRead(JsonNode payload, PayloadException error) {
    }

    private static PayloadRead readPayload() {
        try {
            byte[] raw = PayloadReader.read(System.in, PayloadReader.DEFAULT_TIMEOUT, PayloadReader.DEFAULT_MAX_BYTES);
            return new PayloadRead(PayloadReader.parseJson(raw), null);
        } catch (PayloadException e) {
            return new PayloadRead(null, e);
        }
    }

    /**
     * Configuration errors never block the agent: hook mode falls back to
     * defaults and logs the problems (specification section 18.1).
     */
    private LoadedConfig loadConfig(Path root, Map<String, String> env, ConfigGuard guard, String sessionId) {
        Optional<Path> explicitConfig = Optional.ofNullable(parent == null ? null : parent.configFile);
        ConfigSources sources = ConfigSources.standard(root, explicitConfig,
                Path.of(System.getProperty("user.home")), env);
        if (explicitConfig.isEmpty() && guard.hasBaseline(sessionId)) {
            // The configuration of the session is the one it began with: an agent editing javaguard.yml changes nothing.
            sources = new ConfigSources(guard.frozenUserFile(sessionId), guard.frozenProjectFile(sessionId), false, env, Map.of());
        }
        try {
            LoadedConfig loaded = ConfigLoader.load(sources);
            loaded.warnings().forEach(w -> LOG.warning(w));
            return loaded;
        } catch (ConfigException e) {
            LOG.warning(() -> e.getMessage() + "\nUsing built-in defaults for this run.");
            try {
                return ConfigLoader.load(ConfigSources.defaultsOnly());
            } catch (ConfigException impossible) {
                throw new IllegalStateException("Built-in defaults are invalid", impossible);
            }
        }
    }

    private Optional<Path> explicitRoot(Map<String, String> env) {
        if (parent != null && parent.projectDir != null) {
            return Optional.of(parent.projectDir.toAbsolutePath().normalize());
        }
        for (String name : new String[] {"CLAUDE_PROJECT_DIR", "GEMINI_PROJECT_DIR"}) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return Optional.of(Path.of(value).toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    /** Full project detection (walk up to the nearest build file) is Phase 2. */
    private static Optional<Path> payloadCwd(JsonNode payload, Path userDir) {
        try {
            return PayloadFields.text(payload, "cwd").map(raw -> PathNormalizer.forCurrentOs().resolve(raw, userDir));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static JsonNode payloadOrEmpty(JsonNode payload) {
        return payload != null ? payload : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    private static <T> Optional<T> parseOption(String name, String value,
                                               java.util.function.Function<String, Optional<T>> parser) {
        if (value == null) {
            return Optional.empty();
        }
        Optional<T> parsed = parser.apply(value);
        if (parsed.isEmpty()) {
            LOG.warning(() -> "Ignoring invalid " + name + " value '" + value + "'");
        }
        return parsed;
    }

    /** Raw UTF-8 bytes: bypasses the console code page (stdout.encoding) on Windows. */
    private static void write(PrintStream stream, String text) {
        if (text.isEmpty()) {
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
        stream.flush();
    }
}
