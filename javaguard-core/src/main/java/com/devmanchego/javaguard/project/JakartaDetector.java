package com.devmanchego.javaguard.project;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a build is on the {@code jakarta.*} namespace (Jakarta EE 9+), read from the build file text: Spring Boot 3+ (the
 * parent, the BOM, the Maven or Gradle plugin), Spring Framework 6+, Hibernate 6+ ({@code org.hibernate.orm}), or a
 * Jakarta API artifact at a version that uses the new packages. A {@code jakarta.*} group id alone is not enough: the
 * Jakarta EE 8 artifacts ({@code jakarta.persistence-api 2.2}, {@code jakarta.validation-api 2.0}) still ship
 * {@code javax.*} packages and are what Spring Boot 2 uses. Unknown versions (a property not defined in the same text, a
 * version catalog) answer "no", so JG-SPR-013 stays silent rather than guessing.
 */
final class JakartaDetector {

    private static final Pattern MAVEN_ARTIFACT = Pattern.compile(
            "<groupId>\\s*([\\w.\\-]+)\\s*</groupId>\\s*<artifactId>\\s*([\\w.\\-]+)\\s*</artifactId>\\s*<version>\\s*([^<\\s]+)\\s*</version>");
    private static final Pattern GRADLE_COORDINATE = Pattern.compile("[\"']([\\w.\\-]+):([\\w.\\-]+):([^\"'\\s:@]+)");
    private static final Pattern GRADLE_BOOT_PLUGIN = Pattern.compile(
            "id\\s*\\(?\\s*[\"']org\\.springframework\\.boot[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern MAJOR = Pattern.compile("^(\\d+)");

    private JakartaDetector() {
    }

    static boolean jakartaNamespace(String buildText) {
        if (buildText.isEmpty()) {
            return false;
        }
        if (buildText.contains("org.hibernate.orm")) {
            return true;
        }
        Matcher plugin = GRADLE_BOOT_PLUGIN.matcher(buildText);
        while (plugin.find()) {
            if (major(plugin.group(1), buildText).filter(v -> v >= 3).isPresent()) {
                return true;
            }
        }
        for (Pattern pattern : new Pattern[] {MAVEN_ARTIFACT, GRADLE_COORDINATE}) {
            Matcher m = pattern.matcher(buildText);
            while (m.find()) {
                if (jakartaArtifact(m.group(1), m.group(2), major(m.group(3), buildText).orElse(-1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jakartaArtifact(String group, String artifact, int major) {
        if (major < 0) {
            return false;
        }
        if (group.equals("org.springframework.boot")) {
            return major >= 3;
        }
        if (group.equals("org.springframework") && artifact.startsWith("spring-")) {
            return major >= 6;
        }
        return switch (group) {
            case "jakarta.persistence", "jakarta.validation", "jakarta.ws.rs" -> major >= 3;
            case "jakarta.servlet" -> major >= 5;
            case "jakarta.annotation", "jakarta.transaction" -> major >= 2;
            default -> false;
        };
    }

    /** The major version, resolving one {@code ${property}} defined in the same text. */
    private static Optional<Integer> major(String version, String text) {
        String value = version;
        if (value.startsWith("${") && value.endsWith("}")) {
            String property = Pattern.quote(value.substring(2, value.length() - 1));
            Matcher defined = Pattern.compile("<" + property + ">\\s*([^<\\s]+)\\s*</" + property + ">").matcher(text);
            if (!defined.find()) {
                return Optional.empty();
            }
            value = defined.group(1);
        }
        Matcher m = MAJOR.matcher(value);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }
}
