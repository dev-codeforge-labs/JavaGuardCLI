package com.devmanchego.javaguard.analysis.deps;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 18: JG-DEP-001, 004 and 005 on Maven and Gradle build files (specification section 11.4). */
class DependencyChecksTest {

    private static String pom(String properties, String... dependencies) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n"
                + (properties.isEmpty() ? "" : "  <properties>" + properties + "</properties>\n")
                + "  <dependencies>\n" + String.join("\n", dependencies) + "\n  </dependencies>\n</project>\n";
    }

    private static String dep(String group, String artifact, String version) {
        return "    <dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>"
                + (version == null ? "" : "<version>" + version + "</version>") + "</dependency>";
    }

    private static List<String> ids(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).toList();
    }

    @Test
    void aNewDependencyIsInfoWithItsCoordinatesAndLine() {
        String before = pom("", dep("org.slf4j", "slf4j-api", "2.0.13"));
        String after = pom("", dep("org.slf4j", "slf4j-api", "2.0.13"), dep("com.google.guava", "guava", "33.0.0-jre"));

        List<Finding> findings = DependencyChecks.check("pom.xml", before, after);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-DEP-001");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.message()).contains("com.google.guava:guava:33.0.0-jre");
            assertThat(f.line()).isEqualTo(5);
        });
    }

    @Test
    void snapshotsRangesAndDynamicVersionsAreUnpinned() {
        String after = pom("", dep("a", "snap", "1.0-SNAPSHOT"), dep("a", "range", "[1.0,2.0)"), dep("a", "latest", "LATEST"),
                dep("a", "release", "RELEASE"), dep("a", "pinned", "1.2.3"));

        List<Finding> findings = DependencyChecks.check("pom.xml", "", after);

        assertThat(findings.stream().filter(f -> f.ruleId().equals("JG-DEP-004")).map(f -> f.detail().get("artifact")).toList())
                .containsExactly("a:snap:1.0-SNAPSHOT", "a:range:[1.0,2.0)", "a:latest:LATEST", "a:release:RELEASE");
        assertThat(findings).filteredOn(f -> f.ruleId().equals("JG-DEP-004")).allMatch(f -> f.severity() == Severity.MAJOR);
    }

    @Test
    void anUnpinnedVersionThatWasAlreadyThereIsNotNew() {
        String before = pom("", dep("a", "snap", "1.0-SNAPSHOT"));

        assertThat(ids(DependencyChecks.check("pom.xml", before, before + "<!-- touched -->"))).isEmpty();
    }

    @Test
    void aVersionThatWentDownIsADowngrade() {
        List<Finding> findings = DependencyChecks.check("pom.xml", pom("", dep("com.fasterxml.jackson.core", "jackson-databind", "2.17.2")),
                pom("", dep("com.fasterxml.jackson.core", "jackson-databind", "2.9.10")));

        assertThat(ids(findings)).containsExactly("JG-DEP-005");
        assertThat(findings.get(0).message()).contains("2.17.2").contains("2.9.10");
    }

    @Test
    void anUpgradeIsNothing() {
        assertThat(DependencyChecks.check("pom.xml", pom("", dep("a", "b", "1.9.0")), pom("", dep("a", "b", "1.10.0")))).isEmpty();
    }

    @Test
    void propertiesDefinedInTheSameFileAreResolved() {
        String before = pom("<jackson.version>2.17.2</jackson.version>", dep("g", "jackson", "${jackson.version}"));
        String after = pom("<jackson.version>2.9.10</jackson.version>", dep("g", "jackson", "${jackson.version}"));

        assertThat(ids(DependencyChecks.check("pom.xml", before, after))).containsExactly("JG-DEP-005");
    }

    @Test
    void aVersionManagedElsewhereIsOnlyEverAnAddition() {
        List<Finding> findings = DependencyChecks.check("pom.xml", pom("", dep("g", "managed", null)), pom("", dep("g", "managed", "1.0")));

        assertThat(findings).isEmpty();
        assertThat(ids(DependencyChecks.check("pom.xml", "", pom("", dep("g", "managed", null))))).containsExactly("JG-DEP-001");
    }

    @Test
    void dependencyManagementIsNotADeclarationOfUse() {
        String after = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><dependencyManagement><dependencies>"
                + dep("g", "bom-entry", "1.0") + "</dependencies></dependencyManagement></project>";

        assertThat(DependencyChecks.check("pom.xml", "", after)).isEmpty();
    }

    @Test
    void gradleNotationsInBothDslsAndNamedArguments() {
        String kts = """
                dependencies {
                    implementation("org.slf4j:slf4j-api:2.0.13")
                    testImplementation("org.junit.jupiter:junit-jupiter:5.+")
                    api(group = "com.acme", name = "core", version = "1.0-SNAPSHOT")
                    // implementation("ghost:commented:1.0")
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.0"))
                }
                """;
        String groovy = """
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.13'
                    runtimeOnly group: 'com.acme', name: 'core', version: '1.0-SNAPSHOT'
                }
                """;

        List<Finding> k = DependencyChecks.check("build.gradle.kts", "", kts);
        List<Finding> g = DependencyChecks.check("app/build.gradle", "", groovy);

        assertThat(k.stream().filter(f -> f.ruleId().equals("JG-DEP-001")).map(f -> f.detail().get("artifact")))
                .containsExactly("org.slf4j:slf4j-api:2.0.13", "org.junit.jupiter:junit-jupiter:5.+", "com.acme:core:1.0-SNAPSHOT");
        assertThat(k.stream().filter(f -> f.ruleId().equals("JG-DEP-004")).map(f -> f.detail().get("artifact")))
                .containsExactly("org.junit.jupiter:junit-jupiter:5.+", "com.acme:core:1.0-SNAPSHOT");
        assertThat(ids(g)).containsExactly("JG-DEP-001", "JG-DEP-001", "JG-DEP-004");
    }

    @Test
    void gradleDowngradeIsReported() {
        List<Finding> findings = DependencyChecks.check("build.gradle", "dependencies { implementation 'g:a:2.0.0' }\n",
                "dependencies { implementation 'g:a:1.5.0' }\n");

        assertThat(ids(findings)).containsExactly("JG-DEP-005");
    }

    @Test
    void unparseableOrUnknownFilesGiveNothing() {
        assertThat(DependencyChecks.check("pom.xml", "", "<project><unclosed>")).isEmpty();
        assertThat(DependencyChecks.check("settings.gradle", "", "include 'a'")).isEmpty();
    }

    @Test
    void versionOrderingBasics() {
        assertThat(VersionComparator.compare("1.10.0", "1.9.0")).isPositive();
        assertThat(VersionComparator.compare("1.0", "1.0.0")).isZero();
        assertThat(VersionComparator.compare("1.0-rc1", "1.0")).isNegative();
        assertThat(VersionComparator.compare("1.0.Final", "1.0")).isZero();
        assertThat(VersionComparator.compare("2.0.0-beta2", "2.0.0-alpha9")).isPositive();
        assertThat(VersionComparator.compare("5.12.2", "5.12.2")).isZero();
        assertThat(VersionComparator.compare("1.0-SNAPSHOT", "1.0")).isNegative();
    }
}
