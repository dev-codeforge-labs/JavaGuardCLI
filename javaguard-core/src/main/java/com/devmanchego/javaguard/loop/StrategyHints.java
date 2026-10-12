package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The agent-facing text that goes with each escalation level (specification &sect;13.3, catalog &sect;6). Level 1:
 * "You have attempted this N times ... Stop repeating that approach: re-read ..., check ..., consider ...". Level 2
 * adds that a second opinion has been requested. Level 3 tells the agent to stop and hand over to the user (the
 * wording &sect;3.3 of the contracts prescribes for agents that would not honor {@code continue:false}).
 */
public final class StrategyHints {

    private static final int MAX_FILES = 3;

    private StrategyHints() {
    }

    /**
     * @param episodeRuns the episode's runs of this gate kind, oldest first, the current one last
     */
    public static String guidance(int level, String detector, int attempts, List<Finding> blocking, List<RunRecord> episodeRuns) {
        if (level >= 3) {
            return "STOP. Do not edit further. Tell the user that JavaGuard halted the session because the same blocking "
                    + "problems persisted for " + attempts + " attempts, and that the full report is in "
                    + ".javaguard/reports/last-report.md.";
        }
        StringBuilder text = new StringBuilder(opening(detector, attempts));
        text.append(' ').append(previousChanges(episodeRuns));
        text.append(" Stop repeating that approach:");
        Set<String> files = new LinkedHashSet<>();
        blocking.forEach(f -> f.file().ifPresent(p -> files.add(slash(p))));
        if (!files.isEmpty()) {
            text.append(" re-read ").append(files.stream().limit(MAX_FILES).collect(Collectors.joining(", "))).append(';');
        }
        target(blocking).ifPresent(t -> text.append(" check ").append(t).append(';'));
        categoryHint(blocking).ifPresent(h -> text.append(" consider: ").append(h));
        if (text.charAt(text.length() - 1) == ';' || text.charAt(text.length() - 1) == ':') {
            text.setLength(text.length() - 1);
            text.append('.');
        }
        if (level == 2) {
            text.append(" A second opinion has been requested.");
        }
        return text.toString();
    }

    private static String opening(String detector, int attempts) {
        return switch (detector) {
            case EscalationMachine.OSCILLATION -> "You have attempted this " + attempts
                    + " times and returned to a version that was already rejected.";
            case EscalationMachine.NO_PROGRESS -> "You have attempted this " + attempts
                    + " times without reducing the number of blocking problems.";
            default -> "You have attempted this " + attempts + " times with the same result.";
        };
    }

    /** Which files the earlier attempts touched, from the content hashes recorded for each run. */
    static String previousChanges(List<RunRecord> episodeRuns) {
        Map<String, Integer> changes = new LinkedHashMap<>();
        for (int i = 1; i < episodeRuns.size(); i++) {
            Map<String, String> before = episodeRuns.get(i - 1).fileHashes();
            episodeRuns.get(i).fileHashes().forEach((path, hash) -> {
                if (!hash.equals(before.get(path))) {
                    changes.merge(path, 1, Integer::sum);
                }
            });
        }
        if (changes.isEmpty()) {
            return "Your previous attempts did not change any file JavaGuard checks.";
        }
        return "Your previous attempts changed " + changes.entrySet().stream().limit(MAX_FILES)
                .map(e -> e.getKey() + " (" + e.getValue() + (e.getValue() == 1 ? " time)" : " times)"))
                .collect(Collectors.joining(", ")) + (changes.size() > MAX_FILES ? " and " + (changes.size() - MAX_FILES) + " more" : "")
                + ".";
    }

    /** The symbol, test or rule to look at, from the first blocking finding that names one. */
    private static Optional<String> target(List<Finding> blocking) {
        for (Finding f : blocking) {
            String symbol = f.detail().get("symbol");
            if (symbol != null) {
                String location = f.detail().get("location");
                return Optional.of("'" + symbol + "'" + (location == null ? "" : " in '" + location + "'"));
            }
            String test = f.detail().get("test");
            if (test != null) {
                return Optional.of("the test " + test);
            }
        }
        return blocking.stream().findFirst().map(f -> f.ruleId() + f.file().map(p -> " at " + slash(p) + ":" + f.line()).orElse(""));
    }

    /** Catalog &sect;6, by the first blocking finding whose category has a hint. */
    static Optional<String> categoryHint(List<Finding> blocking) {
        for (Finding f : blocking) {
            Optional<String> hint = hintFor(f);
            if (hint.isPresent()) {
                return hint;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> hintFor(Finding f) {
        if (f.category() == Category.COMPILATION) {
            if (f.message().contains("cannot find symbol")) {
                String symbol = f.detail().getOrDefault("symbol", "the symbol");
                String location = f.detail().getOrDefault("location", "its declaring class");
                return Optional.of("Check the exact name and signature of " + symbol + " in " + location + ". Read the "
                        + "declaring class instead of guessing; verify imports and the dependency that provides it.");
            }
            if (f.message().contains("incompatible types")) {
                return Optional.of("Read the declared types involved; do not cast blindly.");
            }
            return Optional.empty();
        }
        return switch (f.category()) {
            case TEST_FAILURE -> Optional.of("Read the failing test and the production method it calls. Reproduce the expected "
                    + "value by reasoning from the test's setup, not by changing the test.");
            case DEPENDENCY -> Optional.of("Verify the artifact coordinates exist in the configured repositories; prefer "
                    + "versions already managed by the parent/BOM.");
            case DATABASE -> Optional.of("Put the applied migration back exactly as it is in git HEAD and write the change as a new "
                    + "migration with the next version; never rename, renumber or edit a migration that databases have applied.");
            case SECURITY, RELIABILITY -> Optional.of("Apply the remediation literally; if you believe it is a false positive, "
                    + "add a javaguard:ignore with a concrete reason instead of rewriting unrelated code.");
            default -> Optional.empty();
        };
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
