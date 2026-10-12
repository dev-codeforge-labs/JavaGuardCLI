package com.devmanchego.javaguard.testing;

import com.devmanchego.javaguard.testing.JUnitXmlParser.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 8: Surefire XML reports (specification &sect;10.3 step 4). */
class JUnitXmlParserTest {

    private static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" name="com.acme.moduleb.BarTest" time="0.05" tests="4" errors="1" skipped="1" failures="1">
              <properties><property name="java.version" value="21"/></properties>
              <testcase name="passes" classname="com.acme.moduleb.BarTest" time="0.001"/>
              <testcase name="doublesFooValue" classname="com.acme.moduleb.BarTest" time="0.01">
                <failure message="expected: &lt;84&gt; but was: &lt;42&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected: &lt;84&gt; but was: &lt;42&gt;
            \tat com.acme.moduleb.BarTest.doublesFooValue(BarTest.java:9)
            </failure>
              </testcase>
              <testcase name="explodes" classname="com.acme.moduleb.BarTest" time="0.002">
                <error message="boom" type="java.lang.IllegalStateException">java.lang.IllegalStateException: boom
            \tat com.acme.moduleb.Bar.explode(Bar.java:5)
            </error>
              </testcase>
              <testcase name="ignored" classname="com.acme.moduleb.BarTest" time="0"><skipped/></testcase>
            </testsuite>
            """;

    private static Report parse(String xml) throws Exception {
        return JUnitXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void countsCasesAndExtractsFailuresAndErrors() throws Exception {
        Report report = parse(REPORT);

        assertThat(report.run()).isEqualTo(4);
        assertThat(report.failures()).isEqualTo(1);
        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).hasSize(2);

        JUnitXmlParser.TestOutcome failure = report.failed().get(0);
        assertThat(failure.id()).isEqualTo("com.acme.moduleb.BarTest#doublesFooValue");
        assertThat(failure.error()).isFalse();
        assertThat(failure.message()).isEqualTo("expected: <84> but was: <42>");
        assertThat(failure.exceptionType()).isEqualTo("org.opentest4j.AssertionFailedError");
        assertThat(failure.stackTrace()).contains("BarTest.java:9");

        assertThat(report.failed().get(1).error()).isTrue();
        assertThat(report.failed().get(1).exceptionType()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void aFlakyTestThatPassedOnRerunIsAPass() throws Exception {
        Report report = parse("""
                <testsuite name="x" tests="1">
                  <testcase name="flaky" classname="x.T"><flakyFailure message="m" type="t">trace</flakyFailure></testcase>
                </testsuite>""");

        assertThat(report.run()).isEqualTo(1);
        assertThat(report.failed()).isEmpty();
    }

    @Test
    void doctypeDeclarationsAreRefused() {
        String hostile = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><testsuite><testcase name=\"&e;\" classname=\"a\"/></testsuite>";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parse(hostile)).isInstanceOf(Exception.class);
    }

    @Test
    void onlyReportsWrittenAtOrAfterTheRunStartAreRead(@TempDir Path dir) throws Exception {
        Path stale = Files.writeString(dir.resolve("TEST-com.acme.StaleTest.xml"), REPORT);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Path fresh = Files.writeString(dir.resolve("TEST-com.acme.FreshTest.xml"), REPORT);
        Files.setLastModifiedTime(fresh, FileTime.from(Instant.parse("2026-10-04T12:00:00Z")));
        Files.writeString(dir.resolve("notes.txt"), "ignored");

        Report report = JUnitXmlParser.parseDirectory(dir, Instant.parse("2026-10-04T00:00:00Z"));

        assertThat(report.reportFiles()).isEqualTo(1);
        assertThat(report.run()).isEqualTo(4);
    }

    @Test
    void aMissingDirectoryIsAnEmptyReport(@TempDir Path dir) {
        assertThat(JUnitXmlParser.parseDirectory(dir.resolve("nope"), Instant.EPOCH).reportFiles()).isZero();
    }

    @Test
    void anUnparseableFileIsSkippedNotFatal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("TEST-broken.xml"), "<testsuite><testcase");
        Files.writeString(dir.resolve("TEST-ok.xml"), REPORT);

        Report report = JUnitXmlParser.parseDirectory(dir, Instant.EPOCH);

        assertThat(report.reportFiles()).isEqualTo(1);
        assertThat(report.failed()).hasSize(2);
    }
}
