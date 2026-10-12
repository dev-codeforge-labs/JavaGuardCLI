package com.devmanchego.javaguard.handoff;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.loop.LoopGuard.HistoryLine;
import com.devmanchego.javaguard.model.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the handoff document of specification &sect;14.2 from what the session left on disk. Deterministic: the same
 * request over the same files yields the same text, which is what the golden test relies on. Redaction is not done
 * here; {@link HandoffService} applies it to the finished document.
 */
public final class HandoffBuilder {

    /** Everything a handoff is made of. {@code history} is the session's history, oldest first. */
    public record Request(String agent, String sessionId, String gate, int attempts, String detector, List<Finding> findings,
                          Map<String, Path> files, List<HistoryLine> history, Path sessionDir, Path projectRoot,
                          Optional<Path> transcript) {
        public Request {
            findings = List.copyOf(findings);
            files = Map.copyOf(files);
            history = List.copyOf(history);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WINDOW = 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern POM_RELEASE =
            Pattern.compile("<(?:maven\\.compiler\\.release|java\\.version|maven\\.compiler\\.source)>\\s*(\\d+)");
    private static final Pattern POM_BOOT =
            Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");

    private final JavaGuardConfig.Handoff settings;

    public HandoffBuilder(JavaGuardConfig.Handoff settings) {
        this.settings = settings;
    }

    public String build(Request r) {
        StringBuilder md = new StringBuilder();
        md.append("# JavaGuard handoff - agent stuck on ").append(problemLine(r)).append("\n\n");
        summary(md, r);
        environment(md, r);
        problem(md, r);
        source(md, r);
        attempts(md, r);
        reasoning(md, r);
        ruledOut(md, r);
        request(md);
        return md.toString();
    }

    // --- 1. summary ------------------------------------------------------------------------------------------------

    private static String problemLine(Request r) {
        if (r.findings().isEmpty()) {
            return "an unrecorded problem";
        }
        Finding f = r.findings().get(0);
        String where = f.file().map(p -> " in " + p.toString().replace('\\', '/')).orElse("");
        return f.ruleId() + " " + f.title() + where + (r.findings().size() > 1 ? " (+" + (r.findings().size() - 1) + " more)" : "");
    }

    private static void summary(StringBuilder md, Request r) {
        md.append("## 1. Summary\n");
        md.append("Agent: ").append(r.agent().isEmpty() ? "unknown" : r.agent()).append(" | Session: ").append(r.sessionId())
                .append(" | Gate: ").append(r.gate()).append(" | Attempts: ").append(r.attempts())
                .append(" | Detector: ").append(r.detector().isEmpty() ? "none" : r.detector()).append('\n');
        md.append("Blocking problem(s): ").append(r.findings().size()).append(", unchanged for ").append(r.attempts())
                .append(" attempts.\n\n");
    }

    // --- 2. environment --------------------------------------------------------------------------------------------

    private static void environment(StringBuilder md, Request r) {
        List<String> parts = new ArrayList<>();
        String pom = read(r.projectRoot().resolve("pom.xml"));
        if (!pom.isEmpty()) {
            Matcher release = POM_RELEASE.matcher(pom);
            parts.add(release.find() ? "Java release " + release.group(1) : "Java (release not declared)");
            parts.add(Files.exists(r.projectRoot().resolve("mvnw")) || Files.exists(r.projectRoot().resolve("mvnw.cmd"))
                    ? "Maven (wrapper)" : "Maven");
            Matcher boot = POM_BOOT.matcher(pom);
            if (boot.find()) {
                parts.add("Spring Boot " + boot.group(1).strip());
            }
            String[][] libraries = {{"spring-boot-starter-data-jpa", "Spring Data JPA"}, {"hibernate", "Hibernate"},
                    {"junit-jupiter", "JUnit 5"}, {"mockito", "Mockito"}, {"lombok", "Lombok"}};
            for (String[] lib : libraries) {
                if (pom.contains(lib[0])) {
                    parts.add(lib[1]);
                }
            }
        } else if (Files.exists(r.projectRoot().resolve("build.gradle")) || Files.exists(r.projectRoot().resolve("build.gradle.kts"))) {
            parts.add("Gradle project");
        } else {
            parts.add("Build tool not detected");
        }
        md.append("## 2. Environment\n").append(String.join(", ", parts)).append(".\n\n");
    }

    // --- 3. problem ------------------------------------------------------------------------------------------------

    private static void problem(StringBuilder md, Request r) {
        md.append("## 3. Problem\n");
        if (r.findings().isEmpty()) {
            md.append("No blocking findings were recorded for this session.\n\n");
            return;
        }
        for (Finding f : r.findings()) {
            md.append("### ").append(f.ruleId()).append(" - ").append(f.title()).append(" [").append(f.severity()).append("]\n");
            f.file().ifPresent(p -> md.append("Location: ").append(p.toString().replace('\\', '/'))
                    .append(f.line() > 0 ? ":" + f.line() : "").append('\n'));
            md.append(f.message()).append('\n');
            if (!f.remediation().isBlank()) {
                md.append("Suggested: ").append(f.remediation()).append('\n');
            }
            f.snippet().ifPresent(s -> md.append("```\n").append(s).append("\n```\n"));
            new TreeMap<>(f.detail()).forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));
            md.append('\n');
        }
    }

    // --- 4. relevant source ----------------------------------------------------------------------------------------

    private void source(StringBuilder md, Request r) {
        md.append("## 4. Relevant source\n");
        Map<String, Path> files = new TreeMap<>(r.files());
        for (Finding f : r.findings()) {
            f.file().ifPresent(p -> files.putIfAbsent(p.toString().replace('\\', '/'), r.projectRoot().resolve(p)));
        }
        if (files.isEmpty()) {
            md.append("No source files are associated with this problem.\n\n");
            return;
        }
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            String text = read(entry.getValue());
            md.append("### ").append(entry.getKey()).append('\n');
            if (text.isEmpty()) {
                md.append("(file missing or unreadable)\n\n");
                continue;
            }
            String[] lines = text.split("\\R", -1);
            if (lines.length <= settings.maxFileLines()) {
                md.append("```java\n").append(text.stripTrailing()).append("\n```\n\n");
                continue;
            }
            md.append("File has ").append(lines.length).append(" lines; showing the code around the findings.\n");
            for (int[] window : windows(r, entry.getKey(), lines.length)) {
                md.append("```java\n// lines ").append(window[0]).append('-').append(window[1]).append('\n');
                for (int n = window[0]; n <= window[1]; n++) {
                    md.append(lines[n - 1]).append('\n');
                }
                md.append("```\n");
            }
            md.append('\n');
        }
    }

    private static List<int[]> windows(Request r, String rel, int total) {
        List<int[]> raw = new ArrayList<>();
        for (Finding f : r.findings()) {
            if (f.file().map(p -> p.toString().replace('\\', '/').equals(rel)).orElse(false) && f.line() > 0) {
                raw.add(new int[] {Math.max(1, f.line() - WINDOW), Math.min(total, Math.max(f.line(), f.endLine()) + WINDOW)});
            }
        }
        if (raw.isEmpty()) {
            raw.add(new int[] {1, Math.min(total, 2 * WINDOW)});
        }
        raw.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] w : raw) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && w[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], w[1]);
            } else {
                merged.add(w);
            }
        }
        return merged;
    }

    // --- 5. attempts -----------------------------------------------------------------------------------------------

    /** The blocked runs of the episode: the last {@code attempts} blocked runs of this gate, oldest first. */
    private static List<HistoryLine> episodeRuns(Request r) {
        List<HistoryLine> blocked = r.history().stream().filter(h -> h.gate().equals(r.gate()))
                .filter(h -> "BLOCK".equals(h.verdict()) || "HALT".equals(h.verdict())).toList();
        return blocked.subList(Math.max(0, blocked.size() - Math.max(1, r.attempts())), blocked.size());
    }

    private void attempts(StringBuilder md, Request r) {
        md.append("## 5. Attempts so far\n");
        List<HistoryLine> runs = episodeRuns(r);
        if (runs.isEmpty()) {
            md.append("No attempts were recorded.\n\n");
            return;
        }
        int budget = settings.maxDiffLines();
        Map<String, String> previous = null;
        HistoryLine previousRun = null;
        int number = 0;
        for (HistoryLine run : runs) {
            number++;
            Map<String, String> snapshot = readSnapshot(r.sessionDir(), run.snapshotId());
            String result = previousRun == null ? "first blocked run"
                    : previousRun.blockingSignature().equals(run.blockingSignature()) ? "same error" : "different error";
            md.append("### Attempt ").append(number).append(" (").append(TIME.format(Instant.parse(run.ts()))).append(") - result: ")
                    .append(result).append(" (").append(run.blockingCount()).append(" blocking)\n");
            if (previous == null || previous.isEmpty()) {
                md.append(previous == null ? "(first recorded state of the files; section 4 has the current content)\n\n"
                        : "(the previous state was not kept, so there is no diff; see loop.maxSnapshots)\n\n");
            } else {
                budget = diffs(md, previous, snapshot, budget);
            }
            previous = snapshot;
            previousRun = run;
        }
    }

    private static int diffs(StringBuilder md, Map<String, String> before, Map<String, String> after, int budget) {
        Set<String> names = new TreeSet<>(after.keySet());
        names.addAll(before.keySet());
        boolean any = false;
        for (String name : names) {
            List<String> diff = LineDiff.diff(before.getOrDefault(name, ""), after.getOrDefault(name, ""));
            if (diff.isEmpty()) {
                continue;
            }
            any = true;
            if (budget <= 0) {
                md.append("(diff of ").append(name).append(" omitted: handoff.maxDiffLines reached)\n");
                continue;
            }
            List<String> shown = diff.subList(0, Math.min(diff.size(), budget));
            md.append("```diff\n--- ").append(name).append("\n+++ ").append(name).append('\n');
            shown.forEach(l -> md.append(l).append('\n'));
            if (shown.size() < diff.size()) {
                md.append("... (").append(diff.size() - shown.size()).append(" more diff lines omitted)\n");
            }
            md.append("```\n");
            budget -= shown.size();
        }
        if (!any) {
            md.append("No file changed since the previous attempt.\n");
        }
        md.append('\n');
        return budget;
    }

    /** Relative path to file content for one snapshot; empty when it was not kept. */
    private static Map<String, String> readSnapshot(Path sessionDir, String id) {
        Map<String, String> out = new LinkedHashMap<>();
        if (id == null || id.isEmpty()) {
            return out;
        }
        Path dir = sessionDir.resolve("snapshots").resolve(id);
        try {
            Map<?, ?> index = JSON.readValue(dir.resolve("index.json").toFile(), Map.class);
            index.forEach((rel, name) -> {
                if (!name.toString().isEmpty()) {
                    out.put(rel.toString(), read(dir.resolve(name.toString())));
                }
            });
        } catch (IOException | RuntimeException e) {
            // A snapshot pruned or damaged just means fewer diffs.
        }
        return out;
    }

    // --- 6. reasoning ----------------------------------------------------------------------------------------------

    private void reasoning(StringBuilder md, Request r) {
        md.append("## 6. Agent's reasoning (excerpts)\n");
        TranscriptExtractor.Excerpts excerpts = TranscriptExtractor.lastAssistantMessages(r.transcript(), settings.transcriptMessages());
        if (excerpts.messages().isEmpty()) {
            md.append("Not available: ").append(excerpts.note().orElse("no excerpts")).append("\n\n");
            return;
        }
        int n = 0;
        for (String message : excerpts.messages()) {
            md.append("> **Message ").append(++n).append(":**\n");
            message.lines().forEach(l -> md.append("> ").append(l).append('\n'));
            md.append('\n');
        }
    }

    // --- 7. ruled out ----------------------------------------------------------------------------------------------

    private static void ruledOut(StringBuilder md, Request r) {
        md.append("## 7. What has been ruled out\n");
        List<HistoryLine> runs = episodeRuns(r);
        Set<String> bullets = new LinkedHashSet<>();
        int start = 0;
        for (int i = 1; i <= runs.size(); i++) {
            boolean end = i == runs.size() || !runs.get(i).blockingSignature().equals(runs.get(start).blockingSignature());
            if (end) {
                if (i - start >= 2) {
                    bullets.add("Attempts " + (start + 1) + "-" + i + " changed the code but the blocking set stayed the same ("
                            + runs.get(start).blockingCount() + " finding(s)); whatever those edits tried does not fix it.");
                }
                start = i;
            }
        }
        if (bullets.isEmpty()) {
            bullets.add("Nothing could be derived: the attempts produced different errors.");
        }
        bullets.forEach(b -> md.append("- ").append(b).append('\n'));
        md.append('\n');
    }

    // --- 8. request ------------------------------------------------------------------------------------------------

    private static void request(StringBuilder md) {
        md.append("""
                ## 8. Request
                You are a second opinion. The previous agent is stuck. Analyse why its approach fails and
                propose a solution. Answer in this exact structure:
                ### Diagnosis
                ### Solution
                (unified diff against the CURRENT files, or complete replacement of the affected methods)
                ### Why the previous attempts failed
                ### Risks / checks to run
                """);
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
