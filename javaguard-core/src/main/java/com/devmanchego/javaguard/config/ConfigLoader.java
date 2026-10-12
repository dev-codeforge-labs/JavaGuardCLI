package com.devmanchego.javaguard.config;

import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.Severity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Loads, merges and validates configuration (specification &sect;18).
 *
 * <p>Layers, lowest to highest precedence: built-in defaults
 * ({@code javaguard-defaults.yml}), user file, project file, environment
 * variables, CLI overrides. Objects are merged key by key; scalars and lists
 * are replaced; {@code null} keeps the lower layer's value.
 *
 * <p>Unknown keys and unmatched {@code JAVAGUARD_*} variables are warnings;
 * wrong types, invalid values and failed validation are errors, all collected
 * and reported together.
 */
public final class ConfigLoader {

    private static final String DEFAULTS_RESOURCE = "javaguard-defaults.yml";
    private static final String ENV_PREFIX = "JAVAGUARD_";
    /** Not configuration: the recursion guard (specification section 1.1). */
    private static final Set<String> RESERVED_ENV = Set.of("JAVAGUARD_DISABLED");
    /** Maps whose keys are user-defined; unknown-key checks stop at them. */
    private static final Set<String> FREE_FORM_MAPS = Set.of("rules", "consult.commands");
    private static final Set<String> CONSULT_AGENTS = Set.of("claude", "gemini", "codex");

    private static final ObjectMapper YAML = JsonMapper.builder(new YAMLFactory())
            .addModule(new Jdk8Module())
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private ConfigLoader() {
    }

    public static LoadedConfig load(ConfigSources sources) throws ConfigException {
        ObjectNode defaults = defaults();
        ObjectNode merged = defaults.deepCopy();
        List<String> warnings = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        if (sources.userFile().isPresent()) {
            applyFile(sources.userFile().get(), false, defaults, merged, warnings);
        }
        if (sources.projectFile().isPresent()) {
            applyFile(sources.projectFile().get(), sources.explicit(), defaults, merged, warnings);
        }
        applyEnvironment(sources.environment(), defaults, merged, warnings);
        applyCliOverrides(sources.cliOverrides(), defaults, merged, problems);
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }

        JavaGuardConfig config = convert(merged, problems);
        if (config != null) {
            problems.addAll(validate(config));
        }
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new LoadedConfig(config, warnings);
    }

    // --- layers ------------------------------------------------------------

    private static ObjectNode defaults() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing built-in resource " + DEFAULTS_RESOURCE);
            }
            return (ObjectNode) YAML.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read built-in configuration defaults", e);
        }
    }

    private static void applyFile(Path file, boolean mustExist, ObjectNode defaults, ObjectNode merged,
                                  List<String> warnings) throws ConfigException {
        if (!Files.isRegularFile(file)) {
            if (mustExist) {
                throw new ConfigException(List.of(file + ": configuration file not found"));
            }
            return;
        }
        JsonNode tree;
        try {
            tree = YAML.readTree(file.toFile());
        } catch (JsonProcessingException e) {
            throw new ConfigException(file + ": malformed YAML: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new ConfigException(file + ": cannot read: " + e.getMessage(), e);
        }
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            return; // empty file or comments only
        }
        if (!tree.isObject()) {
            throw new ConfigException(List.of(file + ": top level must be a mapping of configuration keys"));
        }
        collectUnknownKeys((ObjectNode) tree, defaults, "", file.toString(), warnings);
        deepMerge(merged, (ObjectNode) tree);
    }

    private static void applyEnvironment(Map<String, String> env, ObjectNode defaults, ObjectNode merged,
                                         List<String> warnings) {
        for (Map.Entry<String, String> e : new TreeMap<>(env).entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(ENV_PREFIX) || RESERVED_ENV.contains(name)) {
                continue;
            }
            String[] tokens = name.substring(ENV_PREFIX.length()).toLowerCase(Locale.ROOT).split("_");
            Optional<List<String>> path = matchEnvPath(defaults, tokens);
            if (path.isEmpty()) {
                warnings.add("Environment variable " + name + " does not match any configuration key");
                continue;
            }
            setAt(merged, path.get(), parseScalar(e.getValue()));
        }
    }

    private static void applyCliOverrides(Map<String, String> overrides, ObjectNode defaults, ObjectNode merged,
                                          List<String> problems) {
        for (Map.Entry<String, String> e : new TreeMap<>(overrides).entrySet()) {
            List<String> path = List.of(e.getKey().split("\\."));
            JsonNode target = defaults;
            for (String segment : path) {
                target = target == null ? null : target.get(segment);
            }
            if (target == null || target.isObject()) {
                problems.add("--option " + e.getKey() + ": not a configuration value");
                continue;
            }
            setAt(merged, path, parseScalar(e.getValue()));
        }
    }

    // --- tree helpers ------------------------------------------------------

    private static void collectUnknownKeys(ObjectNode tree, JsonNode reference, String prefix, String source,
                                           List<String> warnings) {
        for (Map.Entry<String, JsonNode> field : tree.properties()) {
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode known = reference.get(field.getKey());
            if (known == null) {
                warnings.add("Unknown configuration key '" + path + "' in " + source);
            } else if (known.isObject() && field.getValue().isObject() && !FREE_FORM_MAPS.contains(path)) {
                collectUnknownKeys((ObjectNode) field.getValue(), known, path, source, warnings);
            }
        }
    }

    private static void deepMerge(ObjectNode target, ObjectNode override) {
        for (Map.Entry<String, JsonNode> field : override.properties()) {
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            JsonNode existing = target.get(field.getKey());
            if (existing != null && existing.isObject() && value.isObject()) {
                deepMerge((ObjectNode) existing, (ObjectNode) value);
            } else {
                target.set(field.getKey(), value.deepCopy());
            }
        }
    }

    /** Case-insensitive walk of the defaults tree, one token per level, ending at a leaf. */
    private static Optional<List<String>> matchEnvPath(JsonNode defaults, String[] tokens) {
        List<String> path = new ArrayList<>();
        JsonNode node = defaults;
        for (String token : tokens) {
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            String match = null;
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String candidate = names.next();
                if (candidate.toLowerCase(Locale.ROOT).equals(token)) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                return Optional.empty();
            }
            path.add(match);
            node = node.get(match);
        }
        return node == null || node.isObject() ? Optional.empty() : Optional.of(path);
    }

    private static void setAt(ObjectNode root, List<String> path, JsonNode value) {
        ObjectNode node = root;
        for (int i = 0; i < path.size() - 1; i++) {
            JsonNode child = node.get(path.get(i));
            if (child == null || !child.isObject()) {
                child = node.putObject(path.get(i));
            }
            node = (ObjectNode) child;
        }
        node.set(path.get(path.size() - 1), value);
    }

    /** A value from the environment or the CLI, typed by YAML rules ("5000" -> int, "[a, b]" -> list). */
    private static JsonNode parseScalar(String raw) {
        if (raw == null || raw.isEmpty()) {
            return TextNode.valueOf("");
        }
        try {
            JsonNode node = YAML.readTree(raw);
            return node == null || node.isMissingNode() ? TextNode.valueOf(raw) : node;
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(raw);
        }
    }

    // --- conversion --------------------------------------------------------

    private static JavaGuardConfig convert(ObjectNode merged, List<String> problems) {
        ObjectNode withoutRules = merged.deepCopy();
        JsonNode rulesNode = withoutRules.remove("rules");
        withoutRules.set("rules", JsonNodeFactory.instance.objectNode());

        JavaGuardConfig base;
        try {
            base = YAML.treeToValue(withoutRules, JavaGuardConfig.class);
        } catch (JsonMappingException e) {
            problems.add(pathOf(e) + ": " + e.getOriginalMessage().lines().findFirst().orElse("invalid value"));
            return null;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            problems.add("configuration: " + e.getMessage());
            return null;
        }
        Map<String, JavaGuardConfig.RuleOverride> rules = parseRules(rulesNode, problems);
        return new JavaGuardConfig(base.exclude(), base.build(), base.fastGate(), base.fullGate(), base.tests(),
                base.errorprone(), base.pmd(), base.spotbugs(), rules, base.suppressions(), base.deps(), base.api(),
                base.arch(), base.report(), base.loop(), base.handoff(), base.consult(), base.notifications(), base.state());
    }

    private static String pathOf(JsonMappingException e) {
        String path = e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."))
                .replace(".[", "[");
        return path.isEmpty() ? "configuration" : path;
    }

    private static Map<String, JavaGuardConfig.RuleOverride> parseRules(JsonNode rulesNode, List<String> problems) {
        Map<String, JavaGuardConfig.RuleOverride> rules = new LinkedHashMap<>();
        if (rulesNode == null || rulesNode.isNull()) {
            return rules;
        }
        if (!rulesNode.isObject()) {
            problems.add("rules: must be a mapping of rule id to overrides");
            return rules;
        }
        for (Map.Entry<String, JsonNode> entry : rulesNode.properties()) {
            String id = entry.getKey();
            String at = "rules." + id;
            JsonNode node = entry.getValue();
            if (!node.isObject()) {
                problems.add(at + ": must be a mapping (enabled, severity, exclude, rule options)");
                continue;
            }
            Optional<Boolean> enabled = Optional.empty();
            Optional<Severity> severity = Optional.empty();
            List<String> exclude = List.of();
            Map<String, Object> options = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> f : node.properties()) {
                JsonNode v = f.getValue();
                switch (f.getKey()) {
                    case "enabled" -> {
                        if (v.isBoolean()) {
                            enabled = Optional.of(v.booleanValue());
                        } else {
                            problems.add(at + ".enabled: must be true or false");
                        }
                    }
                    case "severity" -> {
                        Optional<Severity> parsed = parseSeverity(v);
                        if (parsed.isPresent()) {
                            severity = parsed;
                        } else {
                            problems.add(at + ".severity: must be one of BLOCKER, CRITICAL, MAJOR, INFO");
                        }
                    }
                    case "exclude" -> {
                        Optional<List<String>> globs = stringList(v);
                        if (globs.isPresent()) {
                            exclude = globs.get();
                        } else {
                            problems.add(at + ".exclude: must be a list of path globs");
                        }
                    }
                    default -> options.put(f.getKey(), YAML.convertValue(v, Object.class));
                }
            }
            rules.put(id, new JavaGuardConfig.RuleOverride(enabled, severity, exclude, options));
        }
        return rules;
    }

    private static Optional<Severity> parseSeverity(JsonNode v) {
        if (!v.isTextual()) {
            return Optional.empty();
        }
        String text = v.asText().strip().toUpperCase(Locale.ROOT);
        for (Severity s : Severity.values()) {
            if (s.name().equals(text)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> stringList(JsonNode v) {
        if (!v.isArray()) {
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : v) {
            if (!item.isTextual()) {
                return Optional.empty();
            }
            out.add(item.asText());
        }
        return Optional.of(out);
    }

    // --- validation --------------------------------------------------------

    static List<String> validate(JavaGuardConfig c) {
        List<String> p = new ArrayList<>();
        if (c.exclude().stream().anyMatch(g -> g == null || g.isBlank())) {
            p.add("exclude: globs must not be blank");
        }
        range(p, "fastGate.budgetMillis", c.fastGate().budgetMillis(), 100, 600_000);
        range(p, "fullGate.budgetSeconds", c.fullGate().budgetSeconds(), 1, 3600);
        range(p, "fullGate.maxConsecutiveBlocks", c.fullGate().maxConsecutiveBlocks(), 1, 100);
        range(p, "tests.maxClasses", c.tests().maxClasses(), 1, 10_000);
        range(p, "deps.osv.timeoutSeconds", c.deps().osv().timeoutSeconds(), 1, 60);
        for (int i = 0; i < c.arch().rules().size(); i++) {
            JavaGuardConfig.ArchRule rule = c.arch().rules().get(i);
            if (rule.from() == null || rule.from().isBlank()) {
                p.add("arch.rules[" + i + "].from: must not be blank");
            }
            if (rule.mustNotDependOn().isEmpty()) {
                p.add("arch.rules[" + i + "].mustNotDependOn: must list at least one package pattern");
            }
        }
        range(p, "report.maxFindings", c.report().maxFindings(), 1, 500);
        range(p, "report.maxChars", c.report().maxChars(), 500, 200_000);
        range(p, "loop.repeatThreshold", c.loop().repeatThreshold(), 2, 50);
        range(p, "loop.oscillationWindow", c.loop().oscillationWindow(), 2, 100);
        range(p, "loop.noProgressWindow", c.loop().noProgressWindow(), 2, 100);
        range(p, "loop.maxSnapshots", c.loop().maxSnapshots(), 1, 1000);
        if (c.loop().maxBlockedRuns() < c.loop().repeatThreshold()) {
            p.add("loop.maxBlockedRuns: must be >= loop.repeatThreshold (" + c.loop().repeatThreshold() + ")");
        }
        range(p, "handoff.maxFileLines", c.handoff().maxFileLines(), 10, 100_000);
        range(p, "handoff.maxDiffLines", c.handoff().maxDiffLines(), 10, 100_000);
        range(p, "handoff.transcriptMessages", c.handoff().transcriptMessages(), 0, 100);
        for (String pattern : c.handoff().redactPatterns()) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException | NullPointerException e) {
                p.add("handoff.redactPatterns: invalid regular expression '" + pattern + "'");
            }
        }
        validateConsult(c.consult(), p);
        JavaGuardConfig.Ntfy ntfy = c.notifications().ntfy();
        if (ntfy.enabled() && (ntfy.url() == null
                || !(ntfy.url().startsWith("https://") || ntfy.url().startsWith("http://")))) {
            p.add("notify.ntfy.url: required (http:// or https://) when notify.ntfy.enabled is true");
        }
        range(p, "state.retentionDays", c.state().retentionDays(), 1, 3650);
        return p;
    }

    private static void validateConsult(JavaGuardConfig.Consult consult, List<String> p) {
        if (consult.order().isEmpty()) {
            p.add("consult.order: must list at least one agent");
        }
        if (consult.order().contains(AgentKind.MANUAL)) {
            p.add("consult.order: 'manual' is not an agent that can be consulted");
        }
        if (new HashSet<>(consult.order()).size() != consult.order().size()) {
            p.add("consult.order: agents must not repeat");
        }
        range(p, "consult.timeoutSeconds", consult.timeoutSeconds(), 10, 3600);
        if (consult.claude() == null || consult.claude().model() == null || consult.claude().model().isBlank()) {
            p.add("consult.claude.model: must not be blank");
        }
        for (String agent : consult.commands().keySet()) {
            if (!CONSULT_AGENTS.contains(agent)) {
                p.add("consult.commands." + agent + ": unknown agent (expected claude, gemini or codex)");
            }
        }
    }

    private static void range(List<String> problems, String key, int value, int min, int max) {
        if (value < min || value > max) {
            problems.add(key + ": must be between " + min + " and " + max + " (was " + value + ")");
        }
    }
}
