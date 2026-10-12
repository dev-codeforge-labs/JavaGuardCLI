package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 21: what the sample harness does not say - severities, messages, line numbers, and independence from Spring. */
class ResilienceRulesTest {

    private static List<Finding> analyze(String source, Set<Framework> frameworks) {
        return new AstCheckEngine(frameworks, EntityIndex.empty(), List.of()).analyze("src/main/java/A.java", source);
    }

    private static List<Finding> only(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    @Test
    void anHttpClientWithoutTimeoutsIsCriticalAndNamesTheProblemAtItsLine() {
        String source = "import org.springframework.web.client.RestTemplate;\n\nclass A {\n    RestTemplate http = new RestTemplate();\n}\n";

        List<Finding> findings = only(analyze(source, Set.of()), "JG-RES-001");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.line()).isEqualTo(4);
            assertThat(f.message()).contains("RestTemplate").contains("timeout");
            assertThat(f.remediation()).contains("setConnectTimeout");
        });
    }

    @Test
    void theWebClientRuleAcceptsATimeoutAppliedPerCallInTheSameClass() {
        String source = "class A {\n    Object client = WebClient.create(\"http://x\");\n    Object call() {\n"
                + "        return client.get().retrieve().bodyToMono(String.class).timeout(java.time.Duration.ofSeconds(3));\n    }\n}\n";

        assertThat(only(analyze(source, Set.of()), "JG-RES-001")).isEmpty();
    }

    @Test
    void cachedPoolsAndUnboundedQueuesAreMajorWhileFixedPoolsAreInfo() {
        String source = "import java.util.concurrent.*;\nclass A {\n    ExecutorService a = Executors.newCachedThreadPool();\n"
                + "    ExecutorService b = Executors.newFixedThreadPool(4);\n}\n";

        List<Finding> findings = only(analyze(source, Set.of()), "JG-RES-005");

        assertThat(findings).extracting(Finding::severity).containsExactly(Severity.MAJOR, Severity.INFO);
    }

    @Test
    void remoteCallInATransactionNeedsSpringButTheOtherResilienceRulesDoNot() {
        String source = "class A {\n    private final RestTemplate rest = new RestTemplate();\n"
                + "    @Transactional\n    void m() {\n        rest.getForObject(\"u\", String.class);\n    }\n}\n";

        assertThat(only(analyze(source, Set.of()), "JG-RES-003")).isEmpty();
        assertThat(only(analyze(source, Set.of(Framework.SPRING_BOOT)), "JG-RES-003")).hasSize(1);
        assertThat(only(analyze(source, Set.of()), "JG-RES-001")).hasSize(1);
    }

    @Test
    void aRemoteCallThatIsBothInATransactionAndInALoopIsReportedByBothRules() {
        String source = "class A {\n    private final CatalogClient catalog;\n    @Transactional\n    void m(java.util.List<String> ids) {\n"
                + "        for (String id : ids) {\n            catalog.find(id);\n        }\n    }\n}\n";

        List<Finding> findings = analyze(source, Set.of(Framework.SPRING_BOOT));

        assertThat(only(findings, "JG-RES-003")).hasSize(1);
        assertThat(only(findings, "JG-RES-004")).hasSize(1);
    }

    @Test
    void theLockRuleReportsTheLockLineAndNamesTheLock() {
        String source = "import java.util.concurrent.locks.ReentrantLock;\nclass A {\n    ReentrantLock guard = new ReentrantLock();\n"
                + "    void m() {\n        guard.lock();\n        work();\n        guard.unlock();\n    }\n    void work() { }\n}\n";

        List<Finding> findings = only(analyze(source, Set.of()), "JG-CONC-010");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.line()).isEqualTo(5);
            assertThat(f.message()).contains("guard.unlock()");
        });
    }

    @Test
    void anUnparseableOrEmptyFileCostsNothing() {
        assertThat(analyze("", Set.of())).isEmpty();
    }
}
