package com.devmanchego.javaguard.integrity;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JG-TST-009 for Maven (catalog &sect;1.7): build configuration that stops tests from running or from
 * failing the build. Every build file is reduced to the set of such settings it contains; a setting present
 * now and absent in the baseline is a finding. Recognized: {@code skipTests}, {@code maven.test.skip},
 * {@code maven.test.failure.ignore}, {@code skipITs} as properties; {@code skip}, {@code skipTests},
 * {@code skipExec}, {@code skipITs}, {@code testFailureIgnore} and {@code excludedGroups} in Surefire's and
 * Failsafe's configuration, and each new {@code <exclude>} there; the same switches in
 * {@code .mvn/maven.config}. Profiles and {@code pluginManagement} are included. Gradle: Phase 17.
 */
final class BuildFileChecks {

    /** A weakening setting: {@code key} identifies it, {@code description} says it, {@code needle} finds its line. */
    record Setting(String key, String description, String needle) {
    }

    private static final Set<String> SWITCH_PROPERTIES = Set.of("skipTests", "maven.test.skip", "maven.test.failure.ignore",
            "skipITs", "maven.test.skip.exec", "skipUTs");
    private static final Set<String> PLUGIN_SWITCHES = Set.of("skip", "skipTests", "skipExec", "skipITs", "testFailureIgnore");
    private static final Set<String> TEST_PLUGINS = Set.of("maven-surefire-plugin", "maven-failsafe-plugin");
    private static final Pattern CONFIG_SWITCH = Pattern.compile(
            "-D(skipTests|maven\\.test\\.skip|maven\\.test\\.failure\\.ignore|skipITs|skipUTs)(?:=(\\w+))?(?=\\s|$)");

    private BuildFileChecks() {
    }

    /** Settings of a {@code pom.xml}; empty when the text is not parseable XML. */
    static Optional<Map<String, Setting>> pomSettings(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Map<String, Setting> settings = new LinkedHashMap<>();
            collectProperties(doc.getDocumentElement(), settings);
            collectPlugins(doc.getDocumentElement(), settings);
            return Optional.of(settings);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Settings of {@code .mvn/maven.config}. */
    static Map<String, Setting> configSettings(String text) {
        Map<String, Setting> settings = new LinkedHashMap<>();
        Matcher m = CONFIG_SWITCH.matcher(text);
        while (m.find()) {
            String value = m.group(2);
            if (value == null || value.equalsIgnoreCase("true")) {
                settings.put("config:" + m.group(1), new Setting("config:" + m.group(1),
                        "-D" + m.group(1) + " in .mvn/maven.config", "-D" + m.group(1)));
            }
        }
        return settings;
    }

    private static void collectProperties(Element root, Map<String, Setting> settings) {
        for (Element properties : descendants(root, "properties")) {
            for (Element property : children(properties)) {
                String name = property.getTagName();
                if (SWITCH_PROPERTIES.contains(name) && isTrue(text(property))) {
                    settings.put("property:" + name, new Setting("property:" + name,
                            "<" + name + ">true</" + name + "> in <properties>", "<" + name));
                }
            }
        }
    }

    private static void collectPlugins(Element root, Map<String, Setting> settings) {
        for (Element plugin : descendants(root, "plugin")) {
            String artifactId = children(plugin).stream().filter(e -> e.getTagName().equals("artifactId"))
                    .map(BuildFileChecks::text).findFirst().orElse("");
            if (!TEST_PLUGINS.contains(artifactId)) {
                continue;
            }
            for (Element configuration : descendants(plugin, "configuration")) {
                for (Element option : children(configuration)) {
                    String name = option.getTagName();
                    if (PLUGIN_SWITCHES.contains(name) && isTrue(text(option))) {
                        settings.put("plugin:" + artifactId + ":" + name, new Setting("plugin:" + artifactId + ":" + name,
                                "<" + name + ">true</" + name + "> in " + artifactId, "<" + name));
                    } else if (name.equals("excludedGroups") && !text(option).isEmpty()) {
                        settings.put("plugin:" + artifactId + ":excludedGroups=" + text(option),
                                new Setting("plugin:" + artifactId + ":excludedGroups=" + text(option),
                                        "<excludedGroups>" + text(option) + "</excludedGroups> in " + artifactId, "<excludedGroups"));
                    } else if (name.equals("excludes")) {
                        for (Element exclude : children(option)) {
                            String pattern = text(exclude);
                            settings.put("plugin:" + artifactId + ":exclude=" + pattern,
                                    new Setting("plugin:" + artifactId + ":exclude=" + pattern,
                                            "<exclude>" + pattern + "</exclude> in " + artifactId, pattern));
                        }
                    }
                }
            }
        }
    }

    /** Empty (a bare {@code <skipTests/>}) and {@code true} switch the behavior on; {@code ${expression}} is not a literal. */
    private static boolean isTrue(String value) {
        return value.isEmpty() || value.equalsIgnoreCase("true");
    }

    private static List<Element> descendants(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        java.util.ArrayList<Element> out = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static List<Element> children(Element parent) {
        java.util.ArrayList<Element> out = new java.util.ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String text(Element e) {
        return e.getTextContent() == null ? "" : e.getTextContent().strip();
    }
}
