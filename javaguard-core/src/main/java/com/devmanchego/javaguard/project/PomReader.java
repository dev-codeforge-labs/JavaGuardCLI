package com.devmanchego.javaguard.project;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses {@code pom.xml} into a {@link MavenPom}, tolerantly.
 *
 * <p>Detection must never fail the gate it feeds (specification &sect;1.1
 * rule 3): a missing, malformed, or unusual {@code pom.xml} yields
 * {@link Optional#empty()}, logged, never an exception.
 */
public final class PomReader {

    private static final Logger LOG = Logger.getLogger(PomReader.class.getName());
    private static final String MAVEN_COMPILER_PLUGIN = "maven-compiler-plugin";

    private PomReader() {
    }

    public static Optional<MavenPom> read(Path pomXml) {
        if (!Files.isRegularFile(pomXml)) {
            return Optional.empty();
        }
        try {
            // Defense in depth against XXE (catalog rule JG-SEC-004), even though pom.xml
            // is repo-local content rather than untrusted network input.
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc;
            try (var in = Files.newInputStream(pomXml)) {
                doc = builder.parse(in);
            }
            Element project = doc.getDocumentElement();
            if (project == null || !"project".equals(project.getLocalName() != null ? project.getLocalName() : project.getTagName())) {
                return Optional.empty();
            }
            return Optional.of(parse(project));
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not parse " + pomXml + "; treating as absent.", e);
            return Optional.empty();
        }
    }

    private static MavenPom parse(Element project) {
        Optional<String> parentRelativePath = parentRelativePath(child(project, "parent"));

        List<String> modules = new ArrayList<>();
        child(project, "modules").ifPresent(m -> children(m, "module").forEach(e -> modules.add(text(e))));

        Optional<Element> build = child(project, "build");
        Optional<String> sourceDirectory = build.flatMap(b -> child(b, "sourceDirectory")).map(PomReader::text);
        Optional<String> testSourceDirectory = build.flatMap(b -> child(b, "testSourceDirectory")).map(PomReader::text);

        Map<String, String> properties = new LinkedHashMap<>();
        child(project, "properties").ifPresent(p -> children(p).forEach(e -> properties.put(localName(e), text(e))));

        Optional<Element> compilerPlugin = build.flatMap(b -> child(b, "plugins"))
                .map(plugins -> children(plugins, "plugin"))
                .flatMap(plugins -> plugins.stream()
                        .filter(p -> child(p, "artifactId").map(PomReader::text).filter(MAVEN_COMPILER_PLUGIN::equals).isPresent())
                        .findFirst());
        Optional<Element> compilerConfig = compilerPlugin.flatMap(p -> child(p, "configuration"));
        Optional<String> compilerRelease = compilerConfig.flatMap(c -> child(c, "release")).map(PomReader::text);
        Optional<String> compilerSource = compilerConfig.flatMap(c -> child(c, "source")).map(PomReader::text);
        Optional<String> compilerTarget = compilerConfig.flatMap(c -> child(c, "target")).map(PomReader::text);

        Set<Coordinate> dependencies = new LinkedHashSet<>();
        child(project, "dependencies").ifPresent(deps -> children(deps, "dependency").forEach(dep -> {
            Optional<String> groupId = child(dep, "groupId").map(PomReader::text);
            Optional<String> artifactId = child(dep, "artifactId").map(PomReader::text);
            if (groupId.isPresent() && artifactId.isPresent()) {
                dependencies.add(new Coordinate(groupId.get(), artifactId.get()));
            }
        }));

        return new MavenPom(parentRelativePath, modules, sourceDirectory, testSourceDirectory, properties,
                compilerRelease, compilerSource, compilerTarget, dependencies,
                child(project, "artifactId").map(PomReader::text).filter(a -> !a.isEmpty()));
    }

    /** Maven's own default when {@code <parent>} has no explicit {@code <relativePath>}. */
    private static final String DEFAULT_PARENT_RELATIVE_PATH = "../pom.xml";

    private static Optional<String> parentRelativePath(Optional<Element> parent) {
        return parent.map(p -> child(p, "relativePath").map(PomReader::text).orElse(DEFAULT_PARENT_RELATIVE_PATH));
    }

    // --- minimal DOM helpers: direct children only, tag/local name match ---

    private static Optional<Element> child(Element parent, String name) {
        return children(parent, name).stream().findFirst();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Element e : children(parent)) {
            if (name.equals(localName(e))) {
                result.add(e);
            }
        }
        return result;
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) n);
            }
        }
        return result;
    }

    private static String localName(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }

    private static String text(Element e) {
        return e.getTextContent() == null ? "" : e.getTextContent().strip();
    }
}
