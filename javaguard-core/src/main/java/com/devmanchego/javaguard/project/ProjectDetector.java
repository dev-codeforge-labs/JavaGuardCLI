package com.devmanchego.javaguard.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project structure detection (specification &sect;7): module and reactor
 * roots, source roots, the Java release to compile with, and framework
 * presence. Pure and file-based; never runs the build tool (that is Phase 6/8).
 *
 * <p>Detection never throws for malformed or unusual input: a project
 * JavaGuard cannot make sense of falls back to sane defaults rather than
 * failing the gate (specification &sect;1.1 rule 3).
 */
public final class ProjectDetector {

    private static final String DEFAULT_MAIN_SOURCE = "src/main/java";
    private static final String DEFAULT_TEST_SOURCE = "src/test/java";
    static final int DEFAULT_JAVA_RELEASE = 21;

    private ProjectDetector() {
    }

    /**
     * Deepest ancestor directory (including {@code file}'s own directory,
     * but not {@code file} itself) holding a build file (specification
     * &sect;7 rule 4). {@code file} need not exist.
     */
    public static Optional<Path> findModuleRoot(Path file) {
        Path dir = toStartDirectory(file);
        while (dir != null) {
            if (hasBuildFile(dir)) {
                return Optional.of(dir);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    private static Path toStartDirectory(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return Files.isDirectory(absolute) ? absolute : absolute.getParent();
    }

    private static boolean hasBuildFile(Path dir) {
        return isFile(dir, "pom.xml") || isFile(dir, "build.gradle") || isFile(dir, "build.gradle.kts");
    }

    /**
     * Build tool for {@code dir} (specification &sect;7 rule 2).
     *
     * @param configured {@code build.tool} from configuration; {@link Optional#empty()} means {@code auto}
     */
    public static BuildToolDetection detectBuildTool(Path dir, Optional<BuildTool> configured) {
        boolean hasPom = isFile(dir, "pom.xml");
        boolean hasGradle = isFile(dir, "build.gradle") || isFile(dir, "build.gradle.kts");
        if (hasPom && hasGradle) {
            return BuildToolDetection.of(configured.orElse(BuildTool.MAVEN), true);
        }
        if (hasPom) {
            return BuildToolDetection.of(BuildTool.MAVEN, false);
        }
        if (hasGradle) {
            return BuildToolDetection.of(BuildTool.GRADLE, false);
        }
        return BuildToolDetection.none();
    }

    /**
     * Top-most directory of the reactor {@code moduleRoot} belongs to
     * (specification &sect;7 rule 1): for Maven, climbs through
     * {@code <parent>} links confirmed by the parent's own {@code <modules>}
     * list; for Gradle, the nearest ancestor with {@code settings.gradle(.kts)}.
     * Never fails: an unresolvable or absent parent simply stops the climb,
     * leaving {@code moduleRoot} itself as the answer.
     */
    public static Path findReactorRoot(Path moduleRoot, BuildTool tool) {
        return tool == BuildTool.MAVEN ? findMavenReactorRoot(moduleRoot) : findGradleReactorRoot(moduleRoot);
    }

    private static Path findMavenReactorRoot(Path moduleRoot) {
        Path current = moduleRoot;
        while (true) {
            Optional<MavenPom> pom = PomReader.read(current.resolve("pom.xml"));
            Optional<String> relativePath = pom.flatMap(MavenPom::parentRelativePath);
            if (relativePath.isEmpty()) {
                return current;
            }
            Path resolved = current.resolve(relativePath.get()).normalize();
            Path parentPomFile = Files.isDirectory(resolved) ? resolved.resolve("pom.xml") : resolved;
            Path parentDir = parentPomFile.getParent();
            Optional<MavenPom> parentPom = parentDir == null ? Optional.empty() : PomReader.read(parentPomFile);
            if (parentPom.isEmpty()) {
                return current;
            }
            Path finalCurrent = current;
            boolean confirmed = parentPom.get().modules().stream()
                    .map(m -> parentDir.resolve(m).normalize())
                    .anyMatch(finalCurrent::equals);
            if (!confirmed) {
                return current;
            }
            current = parentDir;
        }
    }

    private static Path findGradleReactorRoot(Path moduleRoot) {
        Path dir = moduleRoot;
        while (dir != null) {
            if (isFile(dir, "settings.gradle") || isFile(dir, "settings.gradle.kts")) {
                return dir;
            }
            dir = dir.getParent();
        }
        return moduleRoot;
    }

    /** Main source roots for {@code moduleRoot} (specification &sect;7 rule 4). */
    public static List<Path> mainSourceRoots(Path moduleRoot, BuildTool tool) {
        return List.of(resolveSourceRoot(moduleRoot, tool, true));
    }

    /** Test source roots for {@code moduleRoot} (specification &sect;7 rule 4). */
    public static List<Path> testSourceRoots(Path moduleRoot, BuildTool tool) {
        return List.of(resolveSourceRoot(moduleRoot, tool, false));
    }

    private static Path resolveSourceRoot(Path moduleRoot, BuildTool tool, boolean main) {
        if (tool == BuildTool.MAVEN) {
            Optional<MavenPom> pom = PomReader.read(moduleRoot.resolve("pom.xml"));
            Optional<String> override = pom.flatMap(main ? MavenPom::sourceDirectory : MavenPom::testSourceDirectory);
            if (override.isPresent()) {
                return moduleRoot.resolve(override.get()).normalize();
            }
        }
        // Gradle custom sourceSets are not parsed here (best-effort scope, specification section 7 rule 4);
        // full Gradle support arrives in Phase 17.
        return moduleRoot.resolve(main ? DEFAULT_MAIN_SOURCE : DEFAULT_TEST_SOURCE).normalize();
    }

    /**
     * Java release to compile {@code moduleRoot} with (specification &sect;7
     * rule 5): the module's own {@code pom.xml}, else the reactor root's,
     * else {@value #DEFAULT_JAVA_RELEASE}. For Gradle, a best-effort regex
     * scan of the build file.
     */
    public static int javaRelease(Path moduleRoot, Path reactorRoot, BuildTool tool) {
        if (tool == BuildTool.MAVEN) {
            OptionalInt own = mavenRelease(moduleRoot);
            if (own.isPresent()) {
                return own.getAsInt();
            }
            if (!reactorRoot.equals(moduleRoot)) {
                OptionalInt inherited = mavenRelease(reactorRoot);
                if (inherited.isPresent()) {
                    return inherited.getAsInt();
                }
            }
            return DEFAULT_JAVA_RELEASE;
        }
        return gradleRelease(moduleRoot).orElse(DEFAULT_JAVA_RELEASE);
    }

    private static OptionalInt mavenRelease(Path moduleRoot) {
        Optional<MavenPom> pomOpt = PomReader.read(moduleRoot.resolve("pom.xml"));
        if (pomOpt.isEmpty()) {
            return OptionalInt.empty();
        }
        MavenPom pom = pomOpt.get();
        Optional<String> release = firstPresent(pom.properties().get("maven.compiler.release"), pom.compilerPluginRelease());
        OptionalInt fromRelease = release.flatMap(ProjectDetector::parseJavaVersion).map(OptionalInt::of).orElse(OptionalInt.empty());
        if (fromRelease.isPresent()) {
            return fromRelease;
        }
        Optional<String> source = firstPresent(pom.properties().get("maven.compiler.source"), pom.compilerPluginSource());
        return source.flatMap(ProjectDetector::parseJavaVersion).map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    private static Optional<String> firstPresent(String a, Optional<String> b) {
        if (a != null && !a.isBlank()) {
            return Optional.of(a);
        }
        return b.filter(s -> !s.isBlank());
    }

    /** Accepts {@code "21"}, {@code "1.8"} (old style, means Java 8). */
    private static Optional<Integer> parseJavaVersion(String raw) {
        String v = raw.strip();
        if (v.startsWith("1.")) {
            v = v.substring(2);
        }
        try {
            return Optional.of(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static final Pattern GRADLE_LANGUAGE_VERSION = Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern GRADLE_VERSION_ENUM = Pattern.compile("JavaVersion\\.VERSION_(\\d+)(?:_(\\d+))?");
    private static final Pattern GRADLE_COMPATIBILITY = Pattern.compile(
            "(?:source|target)Compatibility\\s*=?\\s*['\"]?(?:JavaVersion\\.VERSION_)?(1\\.\\d+|\\d+)['\"]?");

    private static Optional<Integer> gradleRelease(Path moduleRoot) {
        String text = readGradleBuildFile(moduleRoot);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = GRADLE_LANGUAGE_VERSION.matcher(text);
        if (m.find()) {
            return parseJavaVersion(m.group(1));
        }
        m = GRADLE_VERSION_ENUM.matcher(text);
        if (m.find()) {
            // VERSION_1_8 -> "8"; VERSION_17 -> "17"
            return parseJavaVersion(m.group(2) != null ? m.group(2) : m.group(1));
        }
        m = GRADLE_COMPATIBILITY.matcher(text);
        if (m.find()) {
            return parseJavaVersion(m.group(1));
        }
        return Optional.empty();
    }

    /**
     * Frameworks declared as direct dependencies of {@code moduleRoot} (and,
     * for Maven, also of {@code reactorRoot} when different: a shared
     * starter is sometimes declared directly on the parent rather than
     * repeated in every child).
     */
    public static Set<Framework> detectFrameworks(Path moduleRoot, Path reactorRoot, BuildTool tool) {
        if (tool == BuildTool.MAVEN) {
            Set<Coordinate> coordinates = new LinkedHashSet<>();
            PomReader.read(moduleRoot.resolve("pom.xml")).ifPresent(p -> coordinates.addAll(p.dependencies()));
            if (!reactorRoot.equals(moduleRoot)) {
                PomReader.read(reactorRoot.resolve("pom.xml")).ifPresent(p -> coordinates.addAll(p.dependencies()));
            }
            Set<Framework> found = frameworksFromCoordinates(coordinates);
            String text = readText(moduleRoot.resolve("pom.xml")) + (reactorRoot.equals(moduleRoot) ? "" : readText(reactorRoot.resolve("pom.xml")));
            if (JakartaDetector.jakartaNamespace(text)) {
                found.add(Framework.JAKARTA_EE);
            }
            return found;
        }
        // Like a Maven reactor POM, the root build file of a Gradle build usually configures every subproject
        // (subprojects { }, convention plugins applied from the root): its text counts for the module too.
        String gradle = reactorRoot.equals(moduleRoot) ? readGradleBuildFile(moduleRoot)
                : readGradleBuildFile(moduleRoot) + "\n" + readGradleBuildFile(reactorRoot);
        Set<Framework> found = frameworksFromGradleText(gradle);
        if (JakartaDetector.jakartaNamespace(gradle)) {
            found.add(Framework.JAKARTA_EE);
        }
        return found;
    }

    private static final Map<Framework, BiPredicate<String, String>> FRAMEWORK_MATCHERS = frameworkMatchers();

    private static Map<Framework, BiPredicate<String, String>> frameworkMatchers() {
        Map<Framework, BiPredicate<String, String>> m = new EnumMap<>(Framework.class);
        m.put(Framework.SPRING_BOOT, (g, a) -> g.equals("org.springframework.boot"));
        m.put(Framework.SPRING_FRAMEWORK, (g, a) -> g.equals("org.springframework") && a.startsWith("spring-"));
        m.put(Framework.SPRING_DATA_JPA, (g, a) -> (g.equals("org.springframework.data") && a.equals("spring-data-jpa"))
                || (g.equals("org.springframework.boot") && a.equals("spring-boot-starter-data-jpa")));
        m.put(Framework.HIBERNATE, (g, a) -> g.startsWith("org.hibernate"));
        m.put(Framework.JAKARTA_PERSISTENCE, (g, a) -> g.equals("jakarta.persistence"));
        m.put(Framework.JAVAX_PERSISTENCE, (g, a) -> g.equals("javax.persistence"));
        m.put(Framework.LOMBOK, (g, a) -> g.equals("org.projectlombok") && a.equals("lombok"));
        m.put(Framework.MAPSTRUCT, (g, a) -> g.equals("org.mapstruct"));
        m.put(Framework.JUNIT4, (g, a) -> g.equals("junit") && a.equals("junit"));
        m.put(Framework.JUNIT5, (g, a) -> g.equals("org.junit.jupiter"));
        m.put(Framework.MOCKITO, (g, a) -> g.equals("org.mockito"));
        m.put(Framework.TESTCONTAINERS, (g, a) -> g.equals("org.testcontainers"));
        return m;
    }

    private static Set<Framework> frameworksFromCoordinates(Set<Coordinate> coordinates) {
        Set<Framework> found = EnumSet.noneOf(Framework.class);
        for (Coordinate c : coordinates) {
            FRAMEWORK_MATCHERS.forEach((framework, matcher) -> {
                if (matcher.test(c.groupId(), c.artifactId())) {
                    found.add(framework);
                }
            });
        }
        return found;
    }

    /** Gradle equivalent: coarse text search for the same coordinates (best-effort, specification section 7). */
    private static Set<Framework> frameworksFromGradleText(String text) {
        Set<Framework> found = EnumSet.noneOf(Framework.class);
        if (text.isEmpty()) {
            return found;
        }
        if (text.contains("org.springframework.boot")) {
            found.add(Framework.SPRING_BOOT);
        }
        if (Pattern.compile("org\\.springframework:spring-").matcher(text).find()) {
            found.add(Framework.SPRING_FRAMEWORK);
        }
        if (text.contains("spring-data-jpa") || text.contains("spring-boot-starter-data-jpa")) {
            found.add(Framework.SPRING_DATA_JPA);
        }
        if (text.contains("org.hibernate")) {
            found.add(Framework.HIBERNATE);
        }
        if (text.contains("jakarta.persistence")) {
            found.add(Framework.JAKARTA_PERSISTENCE);
        }
        if (text.contains("javax.persistence")) {
            found.add(Framework.JAVAX_PERSISTENCE);
        }
        if (text.contains("org.projectlombok")) {
            found.add(Framework.LOMBOK);
        }
        if (text.contains("org.mapstruct")) {
            found.add(Framework.MAPSTRUCT);
        }
        if (Pattern.compile("[\"']junit:junit[:\"']").matcher(text).find()) {
            found.add(Framework.JUNIT4);
        }
        if (text.contains("org.junit.jupiter")) {
            found.add(Framework.JUNIT5);
        }
        if (text.contains("org.mockito")) {
            found.add(Framework.MOCKITO);
        }
        if (text.contains("org.testcontainers")) {
            found.add(Framework.TESTCONTAINERS);
        }
        return found;
    }

    private static String readText(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    private static String readGradleBuildFile(Path moduleRoot) {
        for (String name : List.of("build.gradle.kts", "build.gradle")) {
            Path file = moduleRoot.resolve(name);
            if (isFile(moduleRoot, name)) {
                try {
                    return Files.readString(file);
                } catch (java.io.IOException e) {
                    return "";
                }
            }
        }
        return "";
    }

    /**
     * Convenience: every piece of {@link ModuleInfo} for the module owning
     * {@code file}, in one call. Empty when no build file is found or the
     * build tool cannot be determined.
     */
    public static Optional<ModuleInfo> detect(Path file, Optional<BuildTool> configuredTool) {
        Optional<Path> moduleRoot = findModuleRoot(file);
        if (moduleRoot.isEmpty()) {
            return Optional.empty();
        }
        BuildToolDetection detection = detectBuildTool(moduleRoot.get(), configuredTool);
        if (detection.tool().isEmpty()) {
            return Optional.empty();
        }
        BuildTool tool = detection.tool().get();
        Path root = moduleRoot.get();
        Path reactorRoot = findReactorRoot(root, tool);
        return Optional.of(new ModuleInfo(
                root,
                reactorRoot,
                tool,
                mainSourceRoots(root, tool),
                testSourceRoots(root, tool),
                javaRelease(root, reactorRoot, tool),
                detectFrameworks(root, reactorRoot, tool)));
    }

    private static boolean isFile(Path dir, String name) {
        return Files.isRegularFile(dir.resolve(name));
    }
}
