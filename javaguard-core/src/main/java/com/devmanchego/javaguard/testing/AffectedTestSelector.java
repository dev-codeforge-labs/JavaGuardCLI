package com.devmanchego.javaguard.testing;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ReactorModules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Chooses the tests to run for a set of changed files (specification &sect;10.3 step 3). A test class
 * is selected when it is
 * <ul>
 *   <li>named after a changed class ({@code FooTest}, {@code FooTests}, {@code TestFoo}, and
 *       {@code FooIT} when integration tests are included),</li>
 *   <li>a source that imports or otherwise references a changed class (same package, or its
 *       qualified name, or a wildcard import of its package, plus the simple name as a token), or</li>
 *   <li>itself changed in the turn.</li>
 * </ul>
 * Test sources are scanned in the modules of the changed files and in the reactor modules that depend
 * on them. Integration tests ({@code *IT}) are excluded unless {@code tests.includeIntegration}.
 * Above {@code tests.maxClasses}, {@code affected} falls back to running whole modules.
 */
public final class AffectedTestSelector {

    private static final Logger LOG = Logger.getLogger(AffectedTestSelector.class.getName());

    private final ReactorModules reactor;
    private final JavaGuardConfig.Tests config;

    public AffectedTestSelector(ReactorModules reactor, JavaGuardConfig.Tests config) {
        this.reactor = reactor;
        this.config = config;
    }

    private record ChangedClass(String packageName, String simpleName) {
    }

    private record TestSource(Path file, Path module, String fqcn, String simpleName, String packageName) {
    }

    public TestSelection select(Collection<Path> changedFiles) {
        List<String> notes = new ArrayList<>();
        if (config.scope() == JavaGuardConfig.TestScope.NONE) {
            return TestSelection.none(config.scope(), List.of("Tests are disabled (tests.scope: none)."));
        }

        Set<Path> changedModules = new LinkedHashSet<>();
        List<ChangedClass> changedMain = new ArrayList<>();
        Set<Path> changedTests = new LinkedHashSet<>();
        for (Path file : changedFiles) {
            Path absolute = file.toAbsolutePath().normalize();
            if (!absolute.toString().endsWith(".java")) {
                continue;
            }
            reactor.moduleOf(absolute).ifPresent(module -> {
                changedModules.add(module.info().moduleRoot());
                if (module.info().isTestSource(absolute)) {
                    changedTests.add(absolute);
                } else if (module.info().isMainSource(absolute)) {
                    classOf(module.info(), absolute).ifPresent(changedMain::add);
                }
            });
        }
        if (changedModules.isEmpty()) {
            return TestSelection.none(config.scope(), List.of());
        }

        Set<Path> scanModules = reactor.withDependents(changedModules);
        Map<String, TestSource> related = new LinkedHashMap<>();
        for (Path moduleRoot : scanModules) {
            reactor.all().stream().filter(m -> m.info().moduleRoot().equals(moduleRoot)).findFirst()
                    .ifPresent(module -> scanModule(module.info(), changedMain, changedTests, related));
        }

        return switch (config.scope()) {
            case ALL -> new TestSelection(config.scope(), List.of(),
                    new LinkedHashSet<>(reactor.all().stream().map(m -> m.info().moduleRoot()).toList()), true,
                    related.keySet(), false, notes);
            case MODULE -> wholeModules(changedModules, related, false, notes);
            default -> affected(changedModules, related, notes);
        };
    }

    private TestSelection affected(Set<Path> changedModules, Map<String, TestSource> related, List<String> notes) {
        if (related.size() > config.maxClasses()) {
            notes.add(related.size() + " related test classes exceed tests.maxClasses (" + config.maxClasses()
                    + "); running every test of the affected modules instead.");
            return wholeModules(changedModules, related, true, notes);
        }
        Set<Path> modules = new LinkedHashSet<>();
        related.values().forEach(t -> modules.add(t.module()));
        return new TestSelection(JavaGuardConfig.TestScope.AFFECTED, new ArrayList<>(related.keySet()), modules,
                false, related.keySet(), false, notes);
    }

    private TestSelection wholeModules(Set<Path> changedModules, Map<String, TestSource> related, boolean fellBack,
                                       List<String> notes) {
        return new TestSelection(JavaGuardConfig.TestScope.MODULE, List.of(), changedModules, true, related.keySet(),
                fellBack, notes);
    }

    // --- scanning -----------------------------------------------------------------------------

    private void scanModule(ModuleInfo module, List<ChangedClass> changedMain, Set<Path> changedTests,
                            Map<String, TestSource> related) {
        for (Path testRoot : module.testSourceRoots()) {
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(testRoot)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java") && Files.isRegularFile(p))
                        .map(p -> source(module, testRoot, p))
                        .filter(t -> config.includeIntegration() || !t.simpleName().endsWith("IT"))
                        .filter(t -> isRelated(t, changedMain, changedTests))
                        .forEach(t -> related.putIfAbsent(t.fqcn(), t));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not scan test sources under " + testRoot, e);
            }
        }
    }

    private boolean isRelated(TestSource test, List<ChangedClass> changedMain, Set<Path> changedTests) {
        if (changedTests.contains(test.file())) {
            return true;
        }
        String content = null;
        for (ChangedClass changed : changedMain) {
            String n = changed.simpleName();
            if (test.simpleName().equals(n + "Test") || test.simpleName().equals(n + "Tests")
                    || test.simpleName().equals("Test" + n)
                    || (config.includeIntegration() && test.simpleName().equals(n + "IT"))) {
                return true;
            }
            if (content == null) {
                content = read(test.file());
            }
            if (references(content, test.packageName(), changed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean references(String content, String testPackage, ChangedClass changed) {
        if (!Pattern.compile("\\b" + Pattern.quote(changed.simpleName()) + "\\b").matcher(content).find()) {
            return false;
        }
        if (changed.packageName().equals(testPackage)) {
            return true;
        }
        String qualified = changed.packageName().isEmpty() ? changed.simpleName()
                : changed.packageName() + "." + changed.simpleName();
        return content.contains(qualified) || content.contains("import " + changed.packageName() + ".*;");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static TestSource source(ModuleInfo module, Path root, Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        String name = relative.getFileName().toString();
        String simple = name.substring(0, name.length() - ".java".length());
        Path dir = relative.getParent();
        String pkg = dir == null ? "" : dir.toString().replace('\\', '/').replace('/', '.');
        String fqcn = pkg.isEmpty() ? simple : pkg + "." + simple;
        return new TestSource(file.toAbsolutePath().normalize(), module.moduleRoot(), fqcn, simple, pkg);
    }

    private static Optional<ChangedClass> classOf(ModuleInfo module, Path file) {
        for (Path root : module.mainSourceRoots()) {
            if (file.startsWith(root)) {
                TestSource s = source(module, root, file);
                return Optional.of(new ChangedClass(s.packageName(), s.simpleName()));
            }
        }
        return Optional.empty();
    }
}
