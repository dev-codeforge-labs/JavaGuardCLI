package com.devmanchego.javaguard.build.maven;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets the text Maven prints on a failed build (specification &sect;10.4): javac diagnostics
 * (multi-line aware), dependency resolution failures, reactor selection failures, and a fallback
 * "last relevant lines" for anything else. Pure text in, findings out, so it is tested against stored
 * samples of real output rather than by running Maven.
 *
 * <p>Maven prints each compile error twice (in the compiler's own list and again in the build
 * failure summary, the second time with {@code [ERROR]}-prefixed continuation lines); the parser
 * merges them into one finding.
 */
public final class MavenOutputParser {

    public static final String DEPENDENCY_RULE_ID = "JG-DEP-003";
    public static final String BUILD_RULE_ID = "BUILD";

    static final int TAIL_LINES = 30;
    /** The same bound, for the Gradle counterpart. */
    public static final int TAIL_LINES_PUBLIC = TAIL_LINES;

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");
    // "[ERROR] /C:/work/x/Foo.java:[12,5] cannot find symbol" (Maven 3.x and 4.x, compiler plugin 3.x).
    private static final Pattern JAVAC_HEADER = Pattern.compile("^\\[ERROR]\\s+(.+?\\.java):\\[(\\d+),(\\d+)]\\s*(.*)$");
    private static final Pattern JAVAC_DETAIL = Pattern.compile("^\\s+(symbol|location|required|found|reason):\\s*(.*)$");
    private static final Pattern MISSING_ARTIFACT = Pattern.compile("Could not find artifact (\\S+)");
    /**
     * The second time Maven resolves an artifact that does not exist it does not ask again: it reports the failure it cached
     * in the local repository ("... was not found in https://... during a previous attempt"), not "Could not find artifact".
     */
    private static final Pattern CACHED_MISSING_ARTIFACT =
            Pattern.compile("(\\S+:\\S+) was not found in \\S+ during a previous attempt");
    private static final Pattern OFFLINE_ARTIFACT = Pattern.compile("in offline mode and the artifact (\\S+) has not been downloaded");
    private static final Pattern UNRESOLVED_LIST = Pattern.compile("([^\\s,]+) \\((?:absent|present, but unavailable)\\)");
    private static final Pattern FAILING_PROJECT = Pattern.compile("Could not resolve dependencies for project ([^\\s:]+:[^\\s:]+:[^\\s:]+:[^\\s:]+)");
    private static final Set<String> HELP_FOOTER_PREFIXES = Set.of(
            "[ERROR] -> [Help", "[ERROR] To see the full stack trace", "[ERROR] Re-run Maven", "[ERROR] For more information",
            "[ERROR] [Help", "[ERROR] After correcting", "[ERROR]   mvn <args>", "[ERROR] Failed to execute goal");

    /**
     * @param compileErrors      {@code JAVAC} findings, one per distinct diagnostic
     * @param dependencyFindings {@code JG-DEP-003} findings, one per unresolved artifact
     * @param resolutionFailed   some dependency could not be resolved
     * @param offlineFailure     resolution failed because {@code -o} forbade a download: retry online
     * @param transferFailure    the network failed, which says nothing about the artifact's existence
     * @param selectionFailed    {@code -pl} named a project Maven could not find: retry on the whole reactor
     * @param tail               the last relevant lines, for an INFRASTRUCTURE finding
     */
    public record Parsed(List<Finding> compileErrors, List<Finding> dependencyFindings, boolean resolutionFailed,
                         boolean offlineFailure, boolean transferFailure, boolean selectionFailed, List<String> tail) {
        public Parsed {
            compileErrors = List.copyOf(compileErrors);
            dependencyFindings = List.copyOf(dependencyFindings);
            tail = List.copyOf(tail);
        }
    }

    private MavenOutputParser() {
    }

    public static Parsed parse(String output, Path projectRoot) {
        List<String> lines = ANSI.matcher(output).replaceAll("").lines().toList();
        List<Finding> compileErrors = parseCompileErrors(lines, projectRoot);

        Set<String> unresolved = new LinkedHashSet<>();
        Map<String, String> projectOf = new LinkedHashMap<>();
        boolean resolutionFailed = false;
        boolean offline = false;
        boolean transfer = false;
        boolean selection = false;
        String currentProject = "";
        for (String line : lines) {
            Matcher project = FAILING_PROJECT.matcher(line);
            if (project.find()) {
                resolutionFailed = true;
                currentProject = project.group(1);
            }
            if (line.contains("No plugin found for prefix") || line.contains("or one of its dependencies could not be resolved")) {
                resolutionFailed = true; // A plugin, not a project dependency: no DEP-003, but online may fix it.
            }
            if (line.contains("Could not resolve dependencies") || line.contains("Failed to collect dependencies")
                    || line.contains("The following artifacts could not be resolved")) {
                resolutionFailed = true;
            }
            Matcher missing = MISSING_ARTIFACT.matcher(line);
            while (missing.find()) {
                resolutionFailed = true;
                unresolved.add(missing.group(1));
                projectOf.putIfAbsent(missing.group(1), currentProject);
            }
            Matcher cachedMissing = CACHED_MISSING_ARTIFACT.matcher(line);
            while (cachedMissing.find()) {
                resolutionFailed = true;
                unresolved.add(cachedMissing.group(1));
                projectOf.putIfAbsent(cachedMissing.group(1), currentProject);
            }
            Matcher offlineArtifact = OFFLINE_ARTIFACT.matcher(line);
            if (offlineArtifact.find()) {
                resolutionFailed = true;
                offline = true;
                unresolved.add(offlineArtifact.group(1));
                projectOf.putIfAbsent(offlineArtifact.group(1), currentProject);
            }
            Matcher list = UNRESOLVED_LIST.matcher(line);
            while (list.find()) {
                String coordinate = list.group(1);
                if (coordinate.chars().filter(ch -> ch == ':').count() >= 2) {
                    unresolved.add(coordinate);
                    projectOf.putIfAbsent(coordinate, currentProject);
                }
            }
            if (line.contains("Could not transfer artifact") || line.contains("UnknownHostException")
                    || line.contains("Connection refused") || line.contains("Network is unreachable")
                    || line.contains("Read timed out") || line.matches(".*Connect to \\S+ .*failed.*")) {
                transfer = true;
            }
            if (line.contains("Could not find the selected project in the reactor")
                    || line.contains("Could not find the specified project")
                    || line.contains("The requested required projects") && line.contains("do not exist") // Maven 4
                    || line.matches(".*Project '.*' could not be found.*")) {
                selection = true;
            }
        }

        List<Finding> dependencyFindings = new ArrayList<>();
        if (resolutionFailed && !offline && !transfer) {
            // A definite "no such artifact" from a reachable repository: very likely a hallucinated coordinate.
            for (String coordinate : unresolved) {
                dependencyFindings.add(dependencyFinding(coordinate, projectOf.getOrDefault(coordinate, "")));
            }
        }
        return new Parsed(compileErrors, dependencyFindings, resolutionFailed, offline, transfer, selection,
                tail(lines));
    }

    /** INFRASTRUCTURE finding for a failure nobody could classify, carrying the last relevant lines. */
    public static Finding buildFailure(String tool, List<String> tail) {
        String message = tool + " failed for a reason JavaGuard could not classify. Last lines:\n" + String.join("\n", tail);
        return new Finding(BUILD_RULE_ID, "Build failed", Category.INFRASTRUCTURE, Severity.INFO, tool.toLowerCase(),
                Optional.empty(), 0, 0, 0, message, "See the build log in .javaguard/logs/.", Optional.empty(), Map.of(),
                Fingerprints.of(BUILD_RULE_ID, "", tail.isEmpty() ? tool : tail.get(tail.size() - 1)));
    }

    // --- javac --------------------------------------------------------------------------------

    private static List<Finding> parseCompileErrors(List<String> lines, Path projectRoot) {
        Map<String, Diagnostic> byKey = new LinkedHashMap<>();
        Diagnostic current = null;
        for (String line : lines) {
            Matcher header = JAVAC_HEADER.matcher(line);
            if (header.matches()) {
                Diagnostic d = new Diagnostic(normalizePath(header.group(1)), Integer.parseInt(header.group(2)),
                        Integer.parseInt(header.group(3)), header.group(4).strip());
                current = byKey.computeIfAbsent(d.key(), k -> d);
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("[") && !line.startsWith("[ERROR]")) {
                current = null; // [INFO]/[WARNING]: the diagnostic block ended.
                continue;
            }
            String body = line.startsWith("[ERROR]") ? line.substring("[ERROR]".length()) : line;
            Matcher detail = JAVAC_DETAIL.matcher(body);
            if (detail.matches()) {
                current.detail.putIfAbsent(detail.group(1), detail.group(2).strip());
            } else if (body.startsWith("  ") && !body.isBlank()) {
                current.more.add(body.strip());
            } else if (!body.isBlank()) {
                current = null; // A new, unrelated log record: the diagnostic block ended.
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (Diagnostic d : byKey.values()) {
            findings.add(d.toFinding(projectRoot));
        }
        return findings;
    }

    private static final class Diagnostic {
        final String file;
        final int line;
        final int column;
        final String message;
        final Map<String, String> detail = new LinkedHashMap<>();
        final List<String> more = new ArrayList<>();

        Diagnostic(String file, int line, int column, String message) {
            this.file = file;
            this.line = line;
            this.column = column;
            this.message = message;
        }

        String key() {
            return file + ":" + line + ":" + column + ":" + message;
        }

        Finding toFinding(Path projectRoot) {
            Optional<Path> relative = relativize(file, projectRoot);
            Map<String, String> d = new LinkedHashMap<>(detail);
            if (!more.isEmpty()) {
                d.put("context", String.join(" ", more));
            }
            String relativeText = relative.map(p -> p.toString().replace('\\', '/')).orElse("");
            return new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "maven", relative,
                    line, column, line, message,
                    com.devmanchego.javaguard.compile.JavacRemediation.forMessage(message,
                            com.devmanchego.javaguard.compile.JavacRemediation.BUILD_DEFAULT),
                    Optional.empty(), d, Fingerprints.of("JAVAC", relativeText, message));
        }
    }

    /** {@code /C:/x/Foo.java} (Maven on Windows) to {@code C:/x/Foo.java}; other paths unchanged. */
    static String normalizePath(String raw) {
        String path = raw.strip();
        if (path.length() > 3 && path.charAt(0) == '/' && Character.isLetter(path.charAt(1)) && path.charAt(2) == ':') {
            return path.substring(1);
        }
        return path;
    }

    private static Optional<Path> relativize(String file, Path projectRoot) {
        try {
            Path absolute = Path.of(file).toAbsolutePath().normalize();
            Path root = projectRoot.toAbsolutePath().normalize();
            if (absolute.startsWith(root)) {
                return Optional.of(root.relativize(absolute));
            }
            return Optional.of(Path.of(absolute.getFileName().toString()));
        } catch (InvalidPathException | NullPointerException e) {
            return Optional.empty();
        }
    }

    // --- dependencies and tail ------------------------------------------------------------------

    private static Finding dependencyFinding(String coordinate, String project) {
        String message = "Dependency " + coordinate + " cannot be resolved from the configured repositories.";
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("artifact", coordinate);
        if (!project.isEmpty()) {
            detail.put("project", project);
        }
        return new Finding(DEPENDENCY_RULE_ID, "Dependency cannot be resolved (possibly hallucinated)",
                Category.DEPENDENCY, Severity.BLOCKER, "maven", Optional.empty(), 0, 0, 0, message,
                "Verify the artifact coordinates exist in the configured repositories; prefer versions already "
                        + "managed by the parent/BOM.",
                Optional.empty(), detail, Fingerprints.of(DEPENDENCY_RULE_ID, "", message));
    }

    private static List<String> tail(List<String> lines) {
        List<String> relevant = lines.stream()
                .map(String::stripTrailing)
                .filter(l -> l.startsWith("[ERROR]") && l.substring("[ERROR]".length()).strip().length() > 0)
                .filter(l -> HELP_FOOTER_PREFIXES.stream().noneMatch(l::startsWith))
                .toList();
        if (relevant.isEmpty()) {
            relevant = lines.stream().map(String::stripTrailing).filter(l -> !l.isBlank()).toList();
        }
        return relevant.subList(Math.max(0, relevant.size() - TAIL_LINES), relevant.size());
    }
}
