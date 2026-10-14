package com.devmanchego.javaguard.build.gradle;

import com.devmanchego.javaguard.build.maven.MavenOutputParser;
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
 * Interprets the text Gradle prints on a failed build (specification &sect;10.4), the counterpart of
 * {@link MavenOutputParser}: javac diagnostics in javac's own format ({@code File.java:12: error: ...} followed by the
 * source line, a caret line and {@code symbol}/{@code location} details), dependency resolution failures, project or
 * task selection failures, and the "What went wrong" section as the tail for anything else. Pure text in, findings
 * out; the result type is Maven's {@link MavenOutputParser.Parsed} so the runners treat both alike.
 */
public final class GradleOutputParser {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    private static final Pattern JAVAC_HEADER = Pattern.compile("^(.+?\\.java):(\\d+):\\s*error:\\s*(.*)$");
    private static final Pattern JAVAC_DETAIL = Pattern.compile("^\\s+(symbol|location|required|found|reason):\\s*(.*)$");
    private static final Pattern JAVAC_COUNT = Pattern.compile("^\\d+ errors?$");
    private static final Pattern COULD_NOT_FIND = Pattern.compile("Could not find ([^\\s:]+:[^\\s:]+:\\S+?)\\.(?:\\s|$)");
    private static final Pattern COULD_NOT_RESOLVE = Pattern.compile("Could not resolve ([^\\s:]+:[^\\s:]+:\\S+?)\\.(?:\\s|$)");
    private static final Pattern OFFLINE = Pattern.compile("No cached version of (\\S+) available for offline mode");
    private static final Pattern IN_CONFIGURATION = Pattern.compile("for configuration '([^']+)'");

    private GradleOutputParser() {
    }

    public static MavenOutputParser.Parsed parse(String output, Path projectRoot) {
        List<String> lines = ANSI.matcher(output).replaceAll("").lines().toList();
        List<Finding> compileErrors = parseCompileErrors(lines, projectRoot);

        Set<String> unresolved = new LinkedHashSet<>();
        Map<String, String> configurationOf = new LinkedHashMap<>();
        boolean resolutionFailed = false;
        boolean offline = false;
        boolean transfer = false;
        boolean selection = false;
        String configuration = "";
        for (String line : lines) {
            Matcher conf = IN_CONFIGURATION.matcher(line);
            if (conf.find() && line.contains("Could not resolve")) {
                configuration = conf.group(1);
            }
            if (line.contains("Could not resolve all") || line.contains("Could not resolve ") && line.contains("for configuration")
                    || line.contains("Could not resolve dependencies") || line.contains("Plugin [id:")
                    || line.contains("Could not resolve plugin")) {
                resolutionFailed = true;
            }
            for (Pattern pattern : List.of(COULD_NOT_FIND, COULD_NOT_RESOLVE)) {
                Matcher m = pattern.matcher(line);
                while (m.find()) {
                    resolutionFailed = true;
                    unresolved.add(m.group(1));
                    configurationOf.putIfAbsent(m.group(1), configuration);
                }
            }
            Matcher cached = OFFLINE.matcher(line);
            if (cached.find()) {
                resolutionFailed = true;
                offline = true;
                unresolved.add(cached.group(1));
                configurationOf.putIfAbsent(cached.group(1), configuration);
            }
            if (line.contains("Could not GET") || line.contains("Could not HEAD") || line.contains("UnknownHostException")
                    || line.contains("Unknown host") || line.contains("Connection refused") || line.contains("Network is unreachable")
                    || line.contains("Read timed out") || line.matches(".*Connect to \\S+ .*failed.*")) {
                transfer = true;
            }
            if (line.matches(".*Project '.*' not found in (root )?project.*") || line.matches(".*Task '.*' not found in (root )?project.*")
                    || line.contains("Cannot locate tasks that match")) {
                selection = true;
            }
        }

        List<Finding> dependencyFindings = new ArrayList<>();
        if (resolutionFailed && !offline && !transfer) {
            for (String coordinate : unresolved) {
                dependencyFindings.add(dependencyFinding(coordinate, configurationOf.getOrDefault(coordinate, "")));
            }
        }
        return new MavenOutputParser.Parsed(compileErrors, dependencyFindings, resolutionFailed, offline, transfer, selection,
                tail(lines));
    }

    // --- javac --------------------------------------------------------------------------------

    private static List<Finding> parseCompileErrors(List<String> lines, Path projectRoot) {
        Map<String, Finding> byKey = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher header = JAVAC_HEADER.matcher(lines.get(i));
            if (!header.matches()) {
                continue;
            }
            String file = header.group(1).strip();
            int line = Integer.parseInt(header.group(2));
            String message = header.group(3).strip();
            int column = 0;
            Map<String, String> detail = new LinkedHashMap<>();
            int j = i + 1;
            // javac prints the source line, then the caret line, then the details.
            if (j < lines.size() && j + 1 < lines.size() && lines.get(j + 1).stripTrailing().endsWith("^")) {
                column = lines.get(j + 1).stripTrailing().length();
                j += 2;
            }
            for (; j < lines.size(); j++) {
                Matcher d = JAVAC_DETAIL.matcher(lines.get(j));
                if (d.matches()) {
                    detail.putIfAbsent(d.group(1), d.group(2).strip());
                } else if (lines.get(j).isBlank() || JAVAC_COUNT.matcher(lines.get(j).strip()).matches()
                        || JAVAC_HEADER.matcher(lines.get(j)).matches()) {
                    break;
                }
            }
            Optional<Path> relative = relativize(file, projectRoot);
            String relativeText = relative.map(p -> p.toString().replace('\\', '/')).orElse("");
            Finding finding = new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "gradle", relative,
                    line, column, line, message,
                    com.devmanchego.javaguard.compile.JavacRemediation.forMessage(message,
                            com.devmanchego.javaguard.compile.JavacRemediation.BUILD_DEFAULT),
                    Optional.empty(), detail, Fingerprints.of("JAVAC", relativeText, message));
            byKey.putIfAbsent(relativeText + ":" + line + ":" + message, finding);
        }
        return new ArrayList<>(byKey.values());
    }

    private static Optional<Path> relativize(String file, Path projectRoot) {
        try {
            Path absolute = Path.of(file).toAbsolutePath().normalize();
            // The build tool prints the real, long spelling of the path (/private/var, a Windows long name); the project root
            // may be spelled through a link or a short name. Only a file really outside the project falls back to its name.
            return Optional.of(com.devmanchego.javaguard.project.ProjectPaths.relativeTo(projectRoot, absolute)
                    .orElseGet(() -> Path.of(absolute.getFileName().toString())));
        } catch (InvalidPathException | NullPointerException e) {
            return Optional.empty();
        }
    }

    // --- dependencies and tail ------------------------------------------------------------------

    private static Finding dependencyFinding(String coordinate, String configuration) {
        String message = "Dependency " + coordinate + " cannot be resolved from the configured repositories.";
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("artifact", coordinate);
        if (!configuration.isEmpty()) {
            detail.put("configuration", configuration);
        }
        return new Finding(MavenOutputParser.DEPENDENCY_RULE_ID, "Dependency cannot be resolved (possibly hallucinated)",
                Category.DEPENDENCY, Severity.BLOCKER, "gradle", Optional.empty(), 0, 0, 0, message,
                "Verify the artifact coordinates exist in the configured repositories; prefer versions already "
                        + "managed by the version catalog or the platform/BOM.",
                Optional.empty(), detail, Fingerprints.of(MavenOutputParser.DEPENDENCY_RULE_ID, "", message));
    }

    /** The "What went wrong" section, else the last non-blank lines. */
    private static List<String> tail(List<String> lines) {
        List<String> relevant = new ArrayList<>();
        boolean inSection = false;
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (line.startsWith("* What went wrong")) {
                inSection = true;
                continue;
            }
            if (inSection && (line.startsWith("* Try") || line.startsWith("* Get more help") || line.startsWith("BUILD FAILED"))) {
                inSection = false;
            }
            if (inSection && !line.isBlank()) {
                relevant.add(line);
            }
        }
        if (relevant.isEmpty()) {
            relevant = lines.stream().map(String::stripTrailing).filter(l -> !l.isBlank()).toList();
        }
        return relevant.subList(Math.max(0, relevant.size() - MavenOutputParser.TAIL_LINES_PUBLIC), relevant.size());
    }
}
