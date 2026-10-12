package com.devmanchego.javaguard.analysis.deps;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dependencies a build file declares (specification &sect;11.4 step 1). Maven: the parsed POM, direct
 * {@code <dependencies>} of the project and of its profiles (not {@code dependencyManagement}: declared availability is
 * not use), with {@code ${property}} versions resolved from the same file's {@code <properties>}. Gradle: best-effort
 * text matching of the usual declaration forms in {@code build.gradle(.kts)}; version-catalog references
 * ({@code libs.foo}) and anything computed are not seen.
 */
public final class DependencyParsers {

    private static final Pattern GRADLE_NOTATION = Pattern.compile(
            "\\b(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|annotationProcessor|kapt"
                    + "|developmentOnly|compile|runtime|testCompile)\\s*\\(?\\s*[\"']([^\"':\\s]+):([^\"':\\s]+)(?::([^\"'\\s]*))?[\"']");
    private static final Pattern GRADLE_NAMED = Pattern.compile(
            "\\b(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|annotationProcessor|kapt"
                    + "|developmentOnly|compile|runtime|testCompile)\\s*\\(?\\s*group\\s*[:=]\\s*[\"']([^\"']+)[\"']\\s*,\\s*name\\s*[:=]\\s*[\"']([^\"']+)[\"']"
                    + "(?:\\s*,\\s*version\\s*[:=]\\s*[\"']([^\"']*)[\"'])?");
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private DependencyParsers() {
    }

    /** Empty when the text is not parseable XML. */
    public static Optional<List<Dependency>> pom(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element project = doc.getDocumentElement();
            Map<String, String> properties = properties(project);
            List<Dependency> out = new ArrayList<>();
            collect(project, properties, out);
            for (Element profile : children(child(project, "profiles").orElse(null), "profile")) {
                Map<String, String> merged = new LinkedHashMap<>(properties);
                merged.putAll(properties(profile));
                collect(profile, merged, out);
            }
            return Optional.of(out);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void collect(Element owner, Map<String, String> properties, List<Dependency> out) {
        for (Element dependency : children(child(owner, "dependencies").orElse(null), "dependency")) {
            String group = text(child(dependency, "groupId").orElse(null));
            String artifact = text(child(dependency, "artifactId").orElse(null));
            if (group.isEmpty() || artifact.isEmpty()) {
                continue;
            }
            out.add(new Dependency(resolve(group, properties), resolve(artifact, properties),
                    resolve(text(child(dependency, "version").orElse(null)), properties),
                    text(child(dependency, "scope").orElse(null))));
        }
    }

    private static Map<String, String> properties(Element owner) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element property : children(child(owner, "properties").orElse(null), null)) {
            out.put(property.getTagName(), text(property));
        }
        return out;
    }

    private static String resolve(String value, Map<String, String> properties) {
        String current = value;
        for (int pass = 0; pass < 5 && current.contains("${"); pass++) {
            Matcher m = PROPERTY.matcher(current);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(properties.getOrDefault(m.group(1), m.group())));
            }
            m.appendTail(sb);
            if (sb.toString().equals(current)) {
                break;
            }
            current = sb.toString();
        }
        return current;
    }

    public static List<Dependency> gradle(String buildFile) {
        String text = buildFile.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)(?<![:\"'])//.*$", "");
        List<Dependency> out = new ArrayList<>();
        Matcher notation = GRADLE_NOTATION.matcher(text);
        while (notation.find()) {
            out.add(new Dependency(notation.group(2), notation.group(3), notation.group(4), notation.group(1)));
        }
        Matcher named = GRADLE_NAMED.matcher(text);
        while (named.find()) {
            out.add(new Dependency(named.group(2), named.group(3), named.group(4), named.group(1)));
        }
        return out;
    }

    /** Dependencies of a build file by its name; empty for anything that is not a Maven or Gradle build file. */
    public static Optional<List<Dependency>> of(String fileName, String content) {
        String name = fileName.replace('\\', '/');
        if (name.endsWith("pom.xml")) {
            return pom(content);
        }
        if (name.endsWith("build.gradle") || name.endsWith("build.gradle.kts")) {
            return Optional.of(gradle(content));
        }
        return Optional.empty();
    }

    // --- DOM helpers ---------------------------------------------------------------------------

    private static Optional<Element> child(Element parent, String tag) {
        return children(parent, tag).stream().findFirst();
    }

    /** Direct child elements, all of them when {@code tag} is null. */
    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && (tag == null || ((Element) node).getTagName().equals(tag))) {
                out.add((Element) node);
            }
        }
        return out;
    }

    private static String text(Element element) {
        return element == null || element.getTextContent() == null ? "" : element.getTextContent().strip();
    }
}
