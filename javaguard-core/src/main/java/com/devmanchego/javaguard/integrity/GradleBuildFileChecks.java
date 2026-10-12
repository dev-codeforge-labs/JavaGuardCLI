package com.devmanchego.javaguard.integrity;

import com.devmanchego.javaguard.integrity.BuildFileChecks.Setting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JG-TST-009 for Gradle (catalog &sect;1.7): build configuration that stops tests from running or from failing the build,
 * read from {@code build.gradle} and {@code build.gradle.kts} by text, because Gradle build files are programs. Works on
 * the body of every block that configures test tasks ({@code test { }}, {@code tasks.test { }},
 * {@code tasks.withType<Test> { }}, {@code tasks.named("test") { }} and the Groovy spellings of the same) and recognizes
 * {@code enabled = false}, {@code onlyIf { false }}, {@code ignoreFailures = true}, and every {@code exclude},
 * {@code excludeTestsMatching}, {@code excludeTags}, {@code excludeCategories} or {@code excludeEngines}, each as a setting
 * keyed by what it says so that only a newly added one is a finding. The one-line spellings
 * ({@code tasks.test.enabled = false}) are matched anywhere. Comments are ignored. Programmatic ways around the tests
 * ({@code -x test} in a script, a task graph listener) are not understood; that is a documented limit.
 */
final class GradleBuildFileChecks {

    private static final Pattern BLOCK_START = Pattern.compile(
            "(?:\\btasks\\s*\\.\\s*(?:test|withType\\s*[<(]\\s*Test\\s*[>)]|named\\s*(?:<\\s*Test\\s*>)?\\s*\\(\\s*[\"']test[\"']\\s*\\)"
                    + "|getByName\\s*\\(\\s*[\"']test[\"']\\s*\\))(?:\\s*\\.\\s*configureEach)?"
                    + "|\\b(?:test|integrationTest)\\b)\\s*(?:\\(\\s*\\))?\\s*\\{");
    private static final Pattern DISABLED = Pattern.compile("\\b(?:enabled|isEnabled)\\s*=\\s*false\\b|\\bsetEnabled\\s*\\(\\s*false\\s*\\)");
    private static final Pattern ONLY_IF_FALSE = Pattern.compile("\\bonlyIf\\s*(?:\\(\\s*)?\\{\\s*false\\s*\\}");
    private static final Pattern IGNORE_FAILURES = Pattern.compile(
            "\\b(?:ignoreFailures|isIgnoreFailures)\\s*=\\s*true\\b|\\bsetIgnoreFailures\\s*\\(\\s*true\\s*\\)");
    private static final Pattern EXCLUDE = Pattern.compile(
            "\\b(exclude|excludeTestsMatching|excludeTags|excludeCategories|excludeEngines)\\s*(?:\\(\\s*)?((?:[\"'][^\"']*[\"']\\s*,?\\s*)+)");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']*)[\"']");
    private static final Pattern ONE_LINE = Pattern.compile(
            "\\btasks\\s*\\.\\s*test\\s*\\.\\s*(?:enabled|isEnabled)\\s*=\\s*false\\b|\\btest\\s*\\.\\s*(?:enabled|isEnabled)\\s*=\\s*false\\b"
                    + "|\\btasks\\s*\\.\\s*test\\s*\\.\\s*(?:ignoreFailures|isIgnoreFailures)\\s*=\\s*true\\b");

    private GradleBuildFileChecks() {
    }

    static Map<String, Setting> settings(String buildFile) {
        String text = stripComments(buildFile);
        Map<String, Setting> settings = new LinkedHashMap<>();
        Matcher block = BLOCK_START.matcher(text);
        int from = 0;
        while (block.find(from)) {
            int open = block.end() - 1;
            int close = matchingBrace(text, open);
            collect(text.substring(open + 1, close < 0 ? text.length() : close), settings);
            from = block.end(); // Nested blocks are scanned again as part of their parent and on their own: keys dedupe.
        }
        Matcher oneLine = ONE_LINE.matcher(text);
        while (oneLine.find()) {
            String spelled = oneLine.group().replaceAll("\\s+", "");
            settings.put("line:" + spelled, new Setting("line:" + spelled, spelled + " in the build file", oneLine.group().strip()));
        }
        return settings;
    }

    private static void collect(String body, Map<String, Setting> settings) {
        Matcher m = DISABLED.matcher(body);
        if (m.find()) {
            settings.put("test:disabled", new Setting("test:disabled", "the test task is disabled (" + m.group().strip() + ")",
                    firstWord(m.group())));
        }
        m = ONLY_IF_FALSE.matcher(body);
        if (m.find()) {
            settings.put("test:onlyIf-false", new Setting("test:onlyIf-false", "the test task has onlyIf { false }", "onlyIf"));
        }
        m = IGNORE_FAILURES.matcher(body);
        if (m.find()) {
            settings.put("test:ignoreFailures", new Setting("test:ignoreFailures",
                    "test failures are ignored (" + m.group().strip() + ")", firstWord(m.group())));
        }
        m = EXCLUDE.matcher(body);
        while (m.find()) {
            Matcher value = QUOTED.matcher(m.group(2));
            while (value.find()) {
                String key = "test:" + m.group(1) + "=" + value.group(1);
                settings.put(key, new Setting(key, m.group(1) + " \"" + value.group(1) + "\" in the test task", value.group(1)));
            }
        }
    }

    private static String firstWord(String matched) {
        Matcher word = Pattern.compile("[A-Za-z]+").matcher(matched);
        return word.find() ? word.group() : matched;
    }

    /** Index of the brace closing the one at {@code open}, ignoring braces inside string literals; -1 when unbalanced. */
    private static int matchingBrace(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)(?<![:\"'])//.*$", "");
    }
}
