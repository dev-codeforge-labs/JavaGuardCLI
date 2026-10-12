package com.devmanchego.javaguard.testing;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads Surefire's {@code TEST-*.xml} reports (the format Gradle's test task also writes,
 * specification &sect;10.3 step 4). Only files modified at or after {@code since} are read, so a report
 * left by an earlier run is never mistaken for this one. A flaky test that passed on rerun is a pass.
 */
public final class JUnitXmlParser {

    private static final Logger LOG = Logger.getLogger(JUnitXmlParser.class.getName());

    /** One failed or errored test case. */
    public record TestOutcome(String className, String methodName, boolean error, String message,
                              String exceptionType, String stackTrace) {
        public String id() {
            return className + "#" + methodName;
        }
    }

    /** Totals over the parsed reports, plus the failed cases. */
    public record Report(int run, int failures, int errors, int skipped, List<TestOutcome> failed, int reportFiles) {
        public Report {
            failed = List.copyOf(failed);
        }

        public static Report empty() {
            return new Report(0, 0, 0, 0, List.of(), 0);
        }

        public Report plus(Report other) {
            List<TestOutcome> all = new ArrayList<>(failed);
            all.addAll(other.failed);
            return new Report(run + other.run, failures + other.failures, errors + other.errors,
                    skipped + other.skipped, all, reportFiles + other.reportFiles);
        }
    }

    private JUnitXmlParser() {
    }

    /** Parses every {@code TEST-*.xml} in {@code reportsDir} modified at or after {@code since}. */
    public static Report parseDirectory(Path reportsDir, Instant since) {
        if (!Files.isDirectory(reportsDir)) {
            return Report.empty();
        }
        Report total = Report.empty();
        try (Stream<Path> files = Files.list(reportsDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("TEST-")
                    && p.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                if (modifiedBefore(file, since)) {
                    continue;
                }
                total = total.plus(parseFile(file).orElse(Report.empty()));
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list test reports in " + reportsDir, e);
        }
        return total;
    }

    public static Optional<Report> parseFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return Optional.of(parse(in));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not parse test report " + file, e);
            return Optional.empty();
        }
    }

    public static Report parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(in);
        int run = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        List<TestOutcome> failed = new ArrayList<>();
        NodeList cases = doc.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element testCase = (Element) cases.item(i);
            run++;
            String className = testCase.getAttribute("classname");
            String name = testCase.getAttribute("name");
            Optional<Element> failure = firstChild(testCase, "failure");
            Optional<Element> error = firstChild(testCase, "error");
            if (failure.isPresent() || error.isPresent()) {
                Element e = failure.orElseGet(error::get);
                boolean isError = failure.isEmpty();
                if (isError) {
                    errors++;
                } else {
                    failures++;
                }
                failed.add(new TestOutcome(className, name, isError, e.getAttribute("message"), e.getAttribute("type"),
                        e.getTextContent() == null ? "" : e.getTextContent().strip()));
            } else if (firstChild(testCase, "skipped").isPresent()) {
                skipped++;
            }
        }
        return new Report(run, failures, errors, skipped, failed, 1);
    }

    private static Optional<Element> firstChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return Optional.of((Element) n);
            }
        }
        return Optional.empty();
    }

    private static boolean modifiedBefore(Path file, Instant since) {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            return modified.toInstant().isBefore(since);
        } catch (IOException e) {
            return true;
        }
    }
}
