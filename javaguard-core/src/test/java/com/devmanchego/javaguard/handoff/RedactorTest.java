package com.devmanchego.javaguard.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 13: seeded secrets never survive redaction, in source or in transcript text (specification section 14.3). */
class RedactorTest {

    @TempDir
    Path root;

    private Redactor redactor(String... custom) {
        return new Redactor(root, List.of(custom));
    }

    @Test
    void credentialsInKnownFormatsAreReplacedByTheirKind() {
        String out = redactor().redact("String key = \"AKIAABCDEFGHIJKLMNOP\";");

        assertThat(out).doesNotContain("AKIAABCDEFGHIJKLMNOP").contains("<redacted:");
    }

    @Test
    void secretNamedAssignmentsLoseTheirValueInSourceAndInTranscript() {
        Redactor redactor = redactor();

        assertThat(redactor.redact("private String dbPassword = \"hunter2hunter2\";")).doesNotContain("hunter2hunter2")
                .contains("dbPassword");
        assertThat(redactor.redact("I set password: s3cr3t-value-123 in the yaml")).doesNotContain("s3cr3t-value-123");
        assertThat(redactor.redact("{\"apiKey\": \"abcd1234efgh5678\"}")).doesNotContain("abcd1234efgh5678");
    }

    @Test
    void placeholdersAreKept() {
        assertThat(redactor().redact("password = \"${DB_PASSWORD}\"")).contains("${DB_PASSWORD}");
    }

    @Test
    void bearerTokensAreRedacted() {
        assertThat(redactor().redact("Authorization: Bearer abcdefghijklmnop1234567890"))
                .doesNotContain("abcdefghijklmnop1234567890").contains("Bearer <redacted:token>");
    }

    @Test
    void customPatternsAreApplied() {
        assertThat(redactor("ACME-\\d{6}").redact("ticket ACME-123456 failed")).isEqualTo("ticket <redacted:custom> failed");
    }

    @Test
    void anInvalidCustomPatternIsIgnored() {
        assertThat(redactor("(unclosed").redact("plain text")).isEqualTo("plain text");
    }

    @Test
    void pathsInsideTheProjectBecomeRelativeAndOthersExternal() {
        String inside = root.resolve("src").resolve("A.java").toString();

        assertThat(redactor().redact("error in " + inside + " at line 3")).isEqualTo("error in src/A.java at line 3");
        assertThat(redactor().redact("see C:\\Users\\someone\\secret\\notes.txt now")).isEqualTo("see <external> now");
        assertThat(redactor().redact("see /home/someone/.ssh/config now")).isEqualTo("see <external> now");
    }
}
