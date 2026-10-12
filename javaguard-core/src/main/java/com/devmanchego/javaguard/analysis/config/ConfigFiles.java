package com.devmanchego.javaguard.analysis.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring Boot configuration files ({@code application*.properties|yml|yaml} and {@code bootstrap*}) read line by line into
 * flattened keys with their line, so findings can point at the line and no YAML library decides what a malformed file
 * means mid-edit. Nested YAML maps become dotted keys; list items get {@code [n]}; block scalars are skipped.
 */
public final class ConfigFiles {

    /** One {@code key = value}; {@code profile} is the profile of the file or of its YAML document, empty when none. */
    public record Entry(String key, String value, int line, String profile) {
        public String leaf() {
            int dot = key.lastIndexOf('.');
            return dot < 0 ? key : key.substring(dot + 1);
        }
    }

    private static final Pattern NAME = Pattern.compile("^(application|bootstrap)(?:-([\\w.-]+))?\\.(properties|ya?ml)$");
    private static final Pattern YAML_KEY = Pattern.compile("^(\\s*)(-\\s+)?((?:\"[^\"]*\"|'[^']*'|[^\\s:#'\"][^:#]*?))\\s*:(?:\\s+(.*))?$");
    private static final Pattern BLOCK_SCALAR = Pattern.compile("^[|>][+-]?\\d*\\s*(#.*)?$");

    private ConfigFiles() {
    }

    public static boolean isConfigFile(String relativePath) {
        String p = relativePath.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1);
        return NAME.matcher(name).matches();
    }

    public static boolean isTestResource(String relativePath) {
        String p = "/" + relativePath.replace('\\', '/');
        return p.contains("/src/test/") || p.contains("/src/it/") || p.contains("/src/integrationTest/");
    }

    /** The profile in the file name: {@code application-dev.yml} is {@code dev}. */
    public static Optional<String> fileProfile(String relativePath) {
        String p = relativePath.replace('\\', '/');
        Matcher m = NAME.matcher(p.substring(p.lastIndexOf('/') + 1));
        return m.matches() ? Optional.ofNullable(m.group(2)) : Optional.empty();
    }

    public static List<Entry> parse(String relativePath, String content) {
        String profile = fileProfile(relativePath).orElse("");
        return relativePath.endsWith(".properties") ? properties(content, profile) : yaml(content, profile);
    }

    // --- .properties ---------------------------------------------------------------------------------

    private static List<Entry> properties(String content, String profile) {
        List<Entry> out = new ArrayList<>();
        List<String> lines = content.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            int start = i;
            StringBuilder logical = new StringBuilder(lines.get(i).stripLeading());
            while (endsWithContinuation(logical) && i + 1 < lines.size()) {
                logical.setLength(logical.length() - 1);
                logical.append(lines.get(++i).stripLeading());
            }
            String line = logical.toString();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int separator = -1;
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '\\') {
                    c++;
                } else if (ch == '=' || ch == ':' || Character.isWhitespace(ch)) {
                    separator = c;
                    break;
                }
            }
            String key = (separator < 0 ? line : line.substring(0, separator)).replace("\\", "").strip();
            String value = separator < 0 ? "" : line.substring(separator + 1).strip();
            if (separator >= 0 && Character.isWhitespace(line.charAt(separator)) && (value.startsWith("=") || value.startsWith(":"))) {
                value = value.substring(1).strip();
            }
            if (!key.isEmpty()) {
                out.add(new Entry(key, value, start + 1, profile));
            }
        }
        return out;
    }

    private static boolean endsWithContinuation(CharSequence line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    // --- YAML ----------------------------------------------------------------------------------------

    /** One open level of the YAML tree: a map key, or a list item ({@code item} true, {@code key} is {@code [n]}). */
    private record Level(int indent, String key, boolean item) {
    }

    private static List<Entry> yaml(String content, String fileProfile) {
        List<Entry> out = new ArrayList<>();
        Deque<Level> stack = new ArrayDeque<>();
        java.util.Map<String, Integer> itemCounts = new java.util.HashMap<>();
        List<String> lines = content.lines().toList();
        int blockIndent = -1;
        int documentStart = 0;
        String profile = fileProfile;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.strip();
            int indent = raw.length() - raw.stripLeading().length();
            if (blockIndent >= 0) {
                if (trimmed.isEmpty() || indent > blockIndent) {
                    continue;
                }
                blockIndent = -1;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("---")) {
                stack.clear();
                itemCounts.clear();
                documentStart = out.size();
                profile = fileProfile;
                continue;
            }
            Matcher m = YAML_KEY.matcher(raw);
            if (!m.matches()) {
                continue; // A scalar list item, a flow collection or something mid-edit.
            }
            int dash = m.group(1).length();
            int keyIndent = dash + (m.group(2) == null ? 0 : m.group(2).length());
            if (m.group(2) != null) {
                // A list item may sit at its parent key's indentation: only deeper levels and the previous item close.
                while (!stack.isEmpty() && (stack.peek().indent() > dash || stack.peek().item() && stack.peek().indent() == dash)) {
                    stack.pop();
                }
                String parent = path(stack);
                int n = itemCounts.merge(parent + "@" + dash, 1, Integer::sum) - 1;
                stack.push(new Level(dash, "[" + n + "]", true));
            } else {
                while (!stack.isEmpty() && stack.peek().indent() >= keyIndent) {
                    stack.pop();
                }
            }
            String key = unquote(m.group(3).strip());
            String full = join(path(stack), key);
            String rawValue = m.group(4) == null ? "" : m.group(4).strip();
            if (BLOCK_SCALAR.matcher(rawValue).matches() && !rawValue.isEmpty()) {
                blockIndent = keyIndent;
                out.add(new Entry(full, "", i + 1, profile));
                continue;
            }
            String value = value(rawValue);
            if (rawValue.isEmpty() || rawValue.startsWith("#")) {
                stack.push(new Level(keyIndent, key, false));
                continue;
            }
            out.add(new Entry(full, value, i + 1, profile));
            if (full.equals("spring.config.activate.on-profile") || full.equals("spring.profiles")) {
                profile = value;
                for (int e = documentStart; e < out.size(); e++) {
                    Entry old = out.get(e);
                    out.set(e, new Entry(old.key(), old.value(), old.line(), value));
                }
            }
        }
        return out;
    }

    private static String path(Deque<Level> stack) {
        StringBuilder result = new StringBuilder();
        java.util.Iterator<Level> it = stack.descendingIterator();
        while (it.hasNext()) {
            Level level = it.next();
            if (level.item()) {
                result.append(level.key());
            } else {
                if (!result.isEmpty()) {
                    result.append('.');
                }
                result.append(level.key());
            }
        }
        return result.toString();
    }

    private static String join(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    private static String value(String text) {
        String v = text.strip();
        if (v.startsWith("\"") || v.startsWith("'")) {
            char quote = v.charAt(0);
            int end = v.indexOf(quote, 1);
            return end > 0 ? v.substring(1, end) : v.substring(1);
        }
        int comment = v.indexOf(" #");
        return (comment >= 0 ? v.substring(0, comment) : v).strip();
    }

    private static String unquote(String key) {
        if (key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"") || key.startsWith("'") && key.endsWith("'"))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    /** Spring's relaxed binding reduced to one comparable form: lower case, no {@code -}, {@code _} or {@code .}. */
    public static String canonical(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("\\[\\d+]", "").replaceAll("[-_.]", "");
    }
}
