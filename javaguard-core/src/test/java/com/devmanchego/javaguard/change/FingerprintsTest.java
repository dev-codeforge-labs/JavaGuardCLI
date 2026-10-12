package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.Fingerprint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintsTest {

    @Test
    void isSixteenLowerCaseHexCharacters() {
        assertThat(Fingerprints.of("JG-SEC-001", "src/A.java", "msg").value()).matches("[0-9a-f]{16}");
    }

    @Test
    void isStableAcrossLineShifts() {
        Fingerprint before = Fingerprints.of("JAVAC", "src/A.java", "A.java:[42,15] cannot find symbol");
        Fingerprint after = Fingerprints.of("JAVAC", "src/A.java", "A.java:[43,15] cannot find symbol");

        assertThat(after).isEqualTo(before);
    }

    @Test
    void differsByRuleFileOrLiteral() {
        Fingerprint base = Fingerprints.of("JG-X", "src/A.java", "expected 3");

        assertThat(Fingerprints.of("JG-Y", "src/A.java", "expected 3")).isNotEqualTo(base);
        assertThat(Fingerprints.of("JG-X", "src/B.java", "expected 3")).isNotEqualTo(base);
        assertThat(Fingerprints.of("JG-X", "src/A.java", "expected 4")).isNotEqualTo(base);
    }

    @Test
    void pathSeparatorsDoNotMatter() {
        assertThat(Fingerprints.of("JG-X", "src\\main\\A.java", "m"))
                .isEqualTo(Fingerprints.of("JG-X", "src/main/A.java", "m"));
    }

    @Test
    void missingPathIsAllowed() {
        assertThat(Fingerprints.of("JG-DEP-003", null, "cannot resolve x"))
                .isEqualTo(Fingerprints.of("JG-DEP-003", "", "cannot resolve x"));
    }

    @Test
    void testFingerprintIgnoresTheFileAndLineOfTheTrace() {
        Fingerprint a = Fingerprints.ofTest("com.acme.FooTest#bar", "org.opentest4j.AssertionFailedError",
                "expected: <1> but was: <2> at FooTest.java:10");
        Fingerprint b = Fingerprints.ofTest("com.acme.FooTest#bar", "org.opentest4j.AssertionFailedError",
                "expected: <1> but was: <2> at FooTest.java:12");

        assertThat(a).isEqualTo(b);
        assertThat(Fingerprints.ofTest("com.acme.FooTest#baz", "org.opentest4j.AssertionFailedError",
                "expected: <1> but was: <2> at FooTest.java:10")).isNotEqualTo(a);
    }
}
