package com.devmanchego.javaguard.analysis.config;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 23: JG-CFG-001 on whole files (the session filter is ScopedAnalysis's, tested with the gates). */
class ConfigSecretCheckTest {

    private static List<Finding> check(String path, String content) {
        return new ConfigSecretCheck().analyze(path, content);
    }

    @Test
    void literalSecretsAreReportedByKeyWithoutRepeatingTheValue() {
        String properties = """
                spring.datasource.password=Sup3rS3cretPw
                app.jwt.secret=4f8a9c0d1e2b3a4c5d6e7f8091a2b3c4
                payment.client-secret=cl1ent-s3cr3t-value
                """;

        List<Finding> findings = check("src/main/resources/application.properties", properties);

        assertThat(findings).extracting(f -> f.detail().get("key"))
                .containsExactly("spring.datasource.password", "app.jwt.secret", "payment.client-secret");
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(Severity.BLOCKER);
            assertThat(f.message() + f.remediation()).doesNotContain("Sup3r").doesNotContain("4f8a9c").doesNotContain("cl1ent");
        });
        assertThat(findings.get(0).remediation()).contains("${SPRING_DATASOURCE_PASSWORD}");
    }

    @Test
    void knownCredentialFormatsAreReportedWhateverTheKey() {
        List<Finding> findings = check("application.yml", "aws:\n  id: AKIAABCDEFGHIJKLMNOP\n");

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message()).contains("AWS access key"));
    }

    @Test
    void referencesPlaceholdersAndThingsThatOnlyMentionASecretAreNotSecrets() {
        String yaml = """
                spring:
                  datasource:
                    password: ${DB_PASSWORD}
                    other-password: ${DB_PASSWORD:}
                  security:
                    oauth2:
                      client:
                        provider:
                          google:
                            token-uri: https://oauth2.googleapis.com/token
                app:
                  api-key-header: X-Api-Key-Value-Header
                  jwt:
                    token-validity-seconds: 86400
                    secret-store-type: PKCS12
                  password: changeme
                  token: <your-token-here>
                  secret-file: classpath:keys/secret.pem
                  password-policy: at least eight characters
                """;

        assertThat(check("application.yml", yaml)).isEmpty();
    }

    @Test
    void testResourcesAndDevelopmentProfilesAreMajor() {
        assertThat(check("src/test/resources/application.properties", "spring.datasource.password=testpassword1\n"))
                .extracting(Finding::severity).containsExactly(Severity.MAJOR);
        assertThat(check("src/main/resources/application-local.yml", "spring.datasource.password: localpassword1\n"))
                .extracting(Finding::severity).containsExactly(Severity.MAJOR);
        String multiDocument = "spring.config.activate.on-profile: dev\nspring.datasource.password: devpassword1\n---\n"
                + "spring.config.activate.on-profile: prod\nspring.datasource.password: Pr0dPassword!\n";
        assertThat(check("src/main/resources/application.yml", multiDocument)).extracting(Finding::severity)
                .containsExactly(Severity.MAJOR, Severity.BLOCKER);
    }

    @Test
    void otherFilesAreIgnored() {
        assertThat(check("src/main/resources/logback.xml", "password=Sup3rS3cretPw")).isEmpty();
    }
}
