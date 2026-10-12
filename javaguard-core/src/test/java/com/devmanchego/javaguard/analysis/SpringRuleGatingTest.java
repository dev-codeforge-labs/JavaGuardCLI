package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.Framework;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 11: "SPR rules silent when Spring absent" (specification &sect;11.2). Every {@code bad/} sample of every
 * JG-SPR rule is run against engines configured with different detected frameworks: the rule must stay silent
 * unless one of the frameworks it requires is present.
 */
class SpringRuleGatingTest {

    private static final Path RULES = Path.of("src/test/resources/rules");
    private static final Set<Framework> NONE = Set.of();
    private static final Set<Framework> TEST_LIBRARIES_ONLY = EnumSet.of(Framework.JUNIT5, Framework.MOCKITO, Framework.LOMBOK);

    private static AstCheckEngine engine(Set<Framework> frameworks) {
        return new AstCheckEngine(frameworks, EntityIndex.empty(), List.of());
    }

    private static List<Path> badSamples(String ruleId) throws IOException {
        try (Stream<Path> files = Files.list(RULES.resolve(ruleId).resolve("bad"))) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static boolean fires(AstCheckEngine engine, Path sample, String ruleId) throws IOException {
        String content = Files.readString(sample, StandardCharsets.UTF_8);
        List<Finding> findings = engine.analyze("src/main/java/" + sample.getFileName(), content);
        return findings.stream().anyMatch(f -> f.ruleId().equals(ruleId));
    }

    private static final List<String> SPRING_RULES = List.of("JG-SPR-001", "JG-SPR-002", "JG-SPR-003", "JG-SPR-005",
            "JG-SPR-006", "JG-SPR-007", "JG-SPR-009", "JG-SPR-010", "JG-SPR-011", "JG-SPR-012", "JG-SPR-014", "JG-SPR-015",
            "JG-SPR-017", "JG-SEC-015", "JG-RES-003");
    /** JPQL is checked wherever it can appear: Spring Data's @Query, or plain JPA's createQuery and @NamedQuery. */
    private static final String SPRING_OR_JPA_RULE = "JG-SPR-016";
    private static final String JAKARTA_RULE = "JG-SPR-013";
    private static final List<String> JPA_RULES = List.of("JG-SPR-004", "JG-SPR-008");

    @Test
    void withoutSpringOrJpaNoSpringRuleFiresOnAnyOfItsBadSamples() throws IOException {
        for (String ruleId : concat(concat(SPRING_RULES, JPA_RULES), List.of(SPRING_OR_JPA_RULE, JAKARTA_RULE))) {
            for (Path sample : badSamples(ruleId)) {
                assertThat(fires(engine(NONE), sample, ruleId)).as("%s on %s with no framework detected", ruleId, sample.getFileName()).isFalse();
                assertThat(fires(engine(TEST_LIBRARIES_ONLY), sample, ruleId))
                        .as("%s on %s with only test libraries detected", ruleId, sample.getFileName()).isFalse();
            }
        }
    }

    @Test
    void springRulesRunOnceSpringBootOrSpringFrameworkIsDetected() throws IOException {
        for (Framework spring : List.of(Framework.SPRING_BOOT, Framework.SPRING_FRAMEWORK)) {
            for (String ruleId : SPRING_RULES) {
                for (Path sample : badSamples(ruleId)) {
                    assertThat(fires(engine(EnumSet.of(spring)), sample, ruleId))
                            .as("%s on %s with %s", ruleId, sample.getFileName(), spring).isTrue();
                }
            }
        }
    }

    @Test
    void jpaRulesRunWithAnyJpaProviderButNotWithSpringAlone() throws IOException {
        for (String ruleId : JPA_RULES) {
            for (Path sample : badSamples(ruleId)) {
                assertThat(fires(engine(EnumSet.of(Framework.SPRING_BOOT)), sample, ruleId))
                        .as("%s on %s with Spring but no JPA", ruleId, sample.getFileName()).isFalse();
                for (Framework jpa : Frameworks.JPA) {
                    assertThat(fires(engine(EnumSet.of(jpa)), sample, ruleId))
                            .as("%s on %s with %s", ruleId, sample.getFileName(), jpa).isTrue();
                }
            }
        }
    }

    @Test
    void jpqlRuleRunsWithSpringOrWithAnyJpaProvider() throws IOException {
        for (Path sample : badSamples(SPRING_OR_JPA_RULE)) {
            for (Framework framework : concatFrameworks(Frameworks.SPRING, Frameworks.JPA)) {
                assertThat(fires(engine(EnumSet.of(framework)), sample, SPRING_OR_JPA_RULE))
                        .as("%s on %s with %s", SPRING_OR_JPA_RULE, sample.getFileName(), framework).isTrue();
            }
        }
    }

    @Test
    void javaxRuleRunsOnlyOnJakartaProjectsNotOnEverySpringOne() throws IOException {
        for (Path sample : badSamples(JAKARTA_RULE)) {
            assertThat(fires(engine(EnumSet.of(Framework.SPRING_BOOT, Framework.HIBERNATE, Framework.JAVAX_PERSISTENCE)), sample, JAKARTA_RULE))
                    .as("%s on %s in a Spring Boot 2 project", JAKARTA_RULE, sample.getFileName()).isFalse();
            assertThat(fires(engine(EnumSet.of(Framework.JAKARTA_EE)), sample, JAKARTA_RULE))
                    .as("%s on %s in a Jakarta project", JAKARTA_RULE, sample.getFileName()).isTrue();
        }
    }

    @Test
    void theBaselineCacheKeyChangesWithTheRulesThatAreActive() {
        String none = engine(NONE).key();
        String spring = engine(EnumSet.of(Framework.SPRING_BOOT)).key();
        String springAndJpa = engine(EnumSet.of(Framework.SPRING_BOOT, Framework.HIBERNATE)).key();

        assertThat(List.of(none, spring, springAndJpa)).doesNotHaveDuplicates();
        assertThat(engine(EnumSet.of(Framework.SPRING_BOOT)).key()).isEqualTo(spring);
    }

    @Test
    void frameworksThatActivateTheSameRulesShareACacheKey() {
        assertThat(engine(EnumSet.of(Framework.SPRING_BOOT)).key())
                .isEqualTo(engine(EnumSet.of(Framework.SPRING_FRAMEWORK, Framework.JUNIT5)).key());
    }

    private static List<Framework> concatFrameworks(Set<Framework> a, Set<Framework> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }
}
