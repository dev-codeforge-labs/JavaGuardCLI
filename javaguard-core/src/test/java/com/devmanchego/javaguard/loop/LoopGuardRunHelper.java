package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One blocked gate run in its own JVM, as the hook does it: {@code <projectRoot> <fileContent>}. Writes the file,
 * records a run blocked by the same finding, prints the resulting verdict and escalation level.
 */
public final class LoopGuardRunHelper {

    private LoopGuardRunHelper() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path file = root.resolve("src/main/java/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, args[1]);
        Finding finding = new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "maven",
                Optional.of(Path.of("src/main/java/A.java")), 1, 1, 1, "cannot find symbol", "fix", Optional.empty(), Map.of(),
                Fingerprints.of("JAVAC", "src/main/java/A.java", "cannot find symbol"));
        GateResult blocked = new GateResult(GateKind.END_OF_TURN, Verdict.BLOCK, List.of(finding), List.of(), List.of(),
                Optional.empty(), Optional.empty(), List.of(), Duration.ZERO);
        LoopGuard guard = new LoopGuard(new JavaGuardPaths(root), new LoopSettings(3, 6, 5, 12, 3), 20, Clock.systemUTC(), false);

        GateResult result = guard.apply(blocked, new LoopGuard.Input("s1", GateKind.END_OF_TURN,
                Map.of("src/main/java/A.java", file), false));

        System.out.println(result.verdict() + " " + result.escalation().map(e -> e.level()).orElse(0));
    }
}
