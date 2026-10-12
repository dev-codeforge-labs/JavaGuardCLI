package com.devmanchego.javaguard.analysis.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The change sets of a Liquibase changelog (XML, YAML, JSON or formatted SQL), each reduced to what Liquibase's checksum
 * covers: the changes themselves, without comments, preconditions, rollback or {@code validCheckSum}, with all whitespace
 * removed (Liquibase normalises whitespace before hashing SQL). A file that is not a changelog, or does not parse, has no
 * change sets: nothing is judged on it.
 */
public final class LiquibaseChangelog {

    /** One change set; {@code key} is {@code author::id}, Liquibase's identity within a file. */
    public record ChangeSet(String key, String id, String author, String content, boolean runOnChange, boolean anyCheckSum,
                            int line) {
    }

    private static final Set<String> NOT_CHECKSUMMED = Set.of("comment", "preConditions", "precondition", "validCheckSum",
            "rollback", "validchecksum");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SQL_CHANGESET = Pattern.compile("^--\\s*changeset\\s+([^:\\s]+):(\\S+)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_IGNORED = Pattern.compile("^--\\s*(rollback|comment|precondition|labels|context|ignoreLines)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_VALID_CHECKSUM = Pattern.compile("^--\\s*validCheckSum\\b.*", Pattern.CASE_INSENSITIVE);

    private LiquibaseChangelog() {
    }

    /** Change sets by key, in file order; empty when the text is not a changelog Liquibase could read. */
    public static Optional<Map<String, ChangeSet>> parse(String fileName, String content) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".sql")) {
                return formattedSql(content);
            }
            if (!content.contains("databaseChangeLog")) {
                return Optional.empty();
            }
            if (lower.endsWith(".xml")) {
                return xml(content);
            }
            ObjectMapper mapper = lower.endsWith(".json") ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
            return tree(mapper.readTree(content), content);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // --- XML -----------------------------------------------------------------------------------------

    private static Optional<Map<String, ChangeSet>> xml(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        Element root = builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        if (!local(root).equals("databaseChangeLog")) {
            return Optional.empty();
        }
        Map<String, ChangeSet> sets = new LinkedHashMap<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e && local(e).equals("changeSet")) {
                String id = e.getAttribute("id");
                String author = e.getAttribute("author");
                StringBuilder canonical = new StringBuilder();
                boolean anyCheckSum = false;
                NodeList changes = e.getChildNodes();
                for (int j = 0; j < changes.getLength(); j++) {
                    if (changes.item(j) instanceof Element change) {
                        if (local(change).equals("validCheckSum")) {
                            anyCheckSum = true;
                        }
                        if (!NOT_CHECKSUMMED.contains(local(change))) {
                            canonical(change, canonical);
                        }
                    }
                }
                boolean runOnChange = "true".equalsIgnoreCase(e.getAttribute("runOnChange"));
                put(sets, new ChangeSet(author + "::" + id, id, author, canonical.toString(), runOnChange, anyCheckSum,
                        lineOf(content, "<(?:\\w+:)?changeSet\\b[^>]*\\bid\\s*=\\s*[\"']" + Pattern.quote(id) + "[\"']")));
            }
        }
        return Optional.of(sets);
    }

    private static void canonical(Element element, StringBuilder out) {
        out.append('<').append(local(element));
        NamedNodeMap attributes = element.getAttributes();
        Map<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node a = attributes.item(i);
            if (!a.getNodeName().startsWith("xmlns")) {
                sorted.put(a.getNodeName(), squeeze(a.getNodeValue()));
            }
        }
        sorted.forEach((k, v) -> out.append(' ').append(k).append('=').append(v));
        out.append('>');
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element e) {
                canonical(e, out);
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                out.append(squeeze(child.getNodeValue()));
            }
        }
        out.append("</>");
    }

    private static String local(Node node) {
        String name = node.getNodeName();
        return name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
    }

    // --- YAML / JSON ---------------------------------------------------------------------------------

    private static Optional<Map<String, ChangeSet>> tree(JsonNode root, String content) {
        JsonNode log = root == null ? null : root.get("databaseChangeLog");
        if (log == null || !log.isArray()) {
            return Optional.empty();
        }
        Map<String, ChangeSet> sets = new LinkedHashMap<>();
        for (JsonNode entry : log) {
            JsonNode set = entry.get("changeSet");
            if (set == null) {
                continue;
            }
            String id = set.path("id").asText();
            String author = set.path("author").asText();
            StringBuilder canonical = new StringBuilder();
            canonical(set.path("changes"), canonical);
            boolean runOnChange = set.path("runOnChange").asBoolean(false);
            boolean anyCheckSum = set.has("validCheckSum");
            put(sets, new ChangeSet(author + "::" + id, id, author, canonical.toString(), runOnChange, anyCheckSum,
                    lineOf(content, "[\"']?id[\"']?\\s*:\\s*[\"']?" + Pattern.quote(id) + "[\"']?\\s*(?:,|$)")));
        }
        return Optional.of(sets);
    }

    private static void canonical(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                sorted.put(field.getKey(), field.getValue());
            }
            out.append('{');
            sorted.forEach((k, v) -> {
                out.append(k).append(':');
                canonical(v, out);
                out.append(',');
            });
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            node.forEach(v -> {
                canonical(v, out);
                out.append(',');
            });
            out.append(']');
        } else if (!node.isMissingNode() && !node.isNull()) {
            out.append(squeeze(node.asText()));
        }
    }

    // --- formatted SQL -------------------------------------------------------------------------------

    private static Optional<Map<String, ChangeSet>> formattedSql(String content) {
        List<String> lines = content.lines().toList();
        String first = lines.stream().filter(l -> !l.isBlank()).findFirst().orElse("");
        if (!first.strip().toLowerCase(Locale.ROOT).replace(" ", "").startsWith("--liquibaseformattedsql")) {
            return Optional.empty();
        }
        Map<String, ChangeSet> sets = new LinkedHashMap<>();
        String author = null;
        String id = null;
        String attributes = "";
        int line = 0;
        boolean anyCheckSum = false;
        StringBuilder body = new StringBuilder();
        List<String> all = new ArrayList<>(lines);
        all.add("--changeset end:of-file");
        for (int i = 0; i < all.size(); i++) {
            String text = all.get(i).strip();
            Matcher header = SQL_CHANGESET.matcher(text);
            if (header.matches()) {
                if (id != null) {
                    boolean runOnChange = attributes.toLowerCase(Locale.ROOT).contains("runonchange:true");
                    put(sets, new ChangeSet(author + "::" + id, id, author, body.toString(), runOnChange, anyCheckSum, line));
                }
                author = header.group(1);
                id = header.group(2);
                attributes = header.group(3);
                line = i + 1;
                anyCheckSum = false;
                body.setLength(0);
            } else if (id != null) {
                if (SQL_VALID_CHECKSUM.matcher(text).matches()) {
                    anyCheckSum = true;
                } else if (!SQL_IGNORED.matcher(text).matches()) {
                    body.append(squeeze(text));
                }
            }
        }
        return Optional.of(sets);
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static void put(Map<String, ChangeSet> sets, ChangeSet set) {
        sets.putIfAbsent(set.key(), set);
    }

    private static String squeeze(String text) {
        return text == null ? "" : WHITESPACE.matcher(text).replaceAll("");
    }

    private static int lineOf(String content, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(content);
        if (!m.find()) {
            return 0;
        }
        int line = 1;
        for (int i = 0; i < m.start(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
