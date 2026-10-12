package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JG-SEC-005's one severity exception (catalog &sect;1.2): MD5/SHA-1 used for
 * a checksum/cache key, not a security hash, is INFO rather than the rule's
 * default CRITICAL. {@link RuleSamplesTest} only checks whether a rule fires,
 * not at what severity, so this is exercised separately.
 */
class Sec005SeverityTest {

    private final AstCheckEngine engine = new AstCheckEngine();

    private Severity severityOf(String source) {
        List<Finding> findings = engine.analyze("src/main/java/X.java", source);
        return findings.stream().filter(f -> f.ruleId().equals("JG-SEC-005")).findFirst()
                .orElseThrow(() -> new AssertionError("JG-SEC-005 did not fire: " + findings)).severity();
    }

    @Test
    void md5NamedForASecurityPurposeIsCritical() {
        String source = """
                import java.security.MessageDigest;
                class X {
                    byte[] hashPassword(String p) throws Exception {
                        MessageDigest passwordDigest = MessageDigest.getInstance("MD5");
                        return passwordDigest.digest(p.getBytes());
                    }
                }
                """;

        assertThat(severityOf(source)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void md5NamedAsAChecksumOrCacheKeyIsInfo() {
        String source = """
                import java.security.MessageDigest;
                class X {
                    byte[] etagCache(byte[] content) throws Exception {
                        MessageDigest etagCache = MessageDigest.getInstance("MD5");
                        return etagCache.digest(content);
                    }
                }
                """;

        assertThat(severityOf(source)).isEqualTo(Severity.INFO);
    }

    @Test
    void methodNameAloneCanAlsoSignalNonSecurityUse() {
        String source = """
                import java.security.MessageDigest;
                class X {
                    byte[] computeChecksum(byte[] content) throws Exception {
                        MessageDigest d = MessageDigest.getInstance("MD5");
                        return d.digest(content);
                    }
                }
                """;

        assertThat(severityOf(source)).isEqualTo(Severity.INFO);
    }
}
