package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.Framework;
import com.devmanchego.javaguard.project.ReactorModules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * End-of-turn half of JG-SPR-015/016 (catalog &sect;1.6): when a turn changes a persistent type (an entity, embeddable or
 * mapped superclass), the repositories and queries the turn did not touch are checked again, because renaming an
 * attribute breaks every derived query and JPQL string that used the old name. Only findings the change introduced are
 * reported: each file is also checked against the module as it was before the turn, and what was already broken then is
 * left to the file's own checks. The changed files themselves are checked by the gates' file-local pass.
 */
public final class RepositoryRecheckAnalyzer implements DeepAnalyzer {

    private static final Logger LOG = Logger.getLogger(RepositoryRecheckAnalyzer.class.getName());
    private static final int MAX_FILES = 200;
    private static final Set<Framework> FRAMEWORKS = Stream.concat(Frameworks.SPRING.stream(), Frameworks.JPA.stream())
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public List<Finding> analyze(Context context) {
        Map<ReactorModules.Module, Map<Path, String>> changedTypes = new LinkedHashMap<>();
        Set<Path> changed = new HashSet<>();
        for (Path file : context.changedFiles()) {
            Path absolute = file.toAbsolutePath().normalize();
            changed.add(absolute);
            if (!absolute.toString().endsWith(".java")) {
                continue;
            }
            Optional<ReactorModules.Module> module = context.reactor().moduleOf(absolute);
            if (module.isEmpty() || !module.get().info().isMainSource(absolute)
                    || module.get().info().frameworks().stream().noneMatch(FRAMEWORKS::contains)) {
                continue;
            }
            String before = context.baselineOf().apply(absolute).orElse("");
            String now = read(absolute);
            if (persistent(before) || persistent(now)) {
                changedTypes.computeIfAbsent(module.get(), m -> new HashMap<>()).put(absolute, before);
            }
        }
        List<Finding> findings = new ArrayList<>();
        long deadline = System.nanoTime() + context.remaining().toNanos();
        for (Map.Entry<ReactorModules.Module, Map<Path, String>> entry : changedTypes.entrySet()) {
            findings.addAll(recheck(context, entry.getKey(), entry.getValue(), changed, deadline));
        }
        return findings;
    }

    private static List<Finding> recheck(Context context, ReactorModules.Module module, Map<Path, String> baselines, Set<Path> changed,
                                         long deadline) {
        List<Path> roots = module.info().mainSourceRoots();
        Set<Framework> frameworks = module.info().frameworks();
        List<AstCheck> checks = List.of(new Spr015DerivedQueryOnUnknownProperty(), new Spr016JpqlOnUnknownEntityOrAttribute());
        AstCheckEngine now = new AstCheckEngine(checks, path -> false, frameworks, EntityIndex.scanning(roots));
        AstCheckEngine before = new AstCheckEngine(checks, path -> false, frameworks, EntityIndex.scanning(roots, baselines));
        String culprits = baselines.keySet().stream().map(p -> p.getFileName().toString()).sorted().collect(Collectors.joining(", "));
        List<Finding> out = new ArrayList<>();
        int checked = 0;
        for (Path file : candidates(roots, changed)) {
            if (checked++ >= MAX_FILES || System.nanoTime() > deadline) {
                LOG.info(() -> "Repository re-check stopped after " + MAX_FILES + " files or the budget");
                break;
            }
            String text = read(file);
            if (!mentionsQueries(text)) {
                continue;
            }
            String relative = context.paths().projectRoot().relativize(file).toString().replace('\\', '/');
            List<Finding> current = now.analyze(relative, text);
            if (current.isEmpty()) {
                continue;
            }
            Set<String> old = before.analyze(relative, text).stream().map(f -> f.ruleId() + ":" + f.line()).collect(Collectors.toSet());
            current.stream().filter(f -> !old.contains(f.ruleId() + ":" + f.line())).map(f -> blame(f, culprits)).forEach(out::add);
        }
        return out;
    }

    private static List<Path> candidates(List<Path> roots, Set<Path> changed) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).map(p -> p.toAbsolutePath().normalize())
                        .filter(p -> !changed.contains(p)).sorted().forEach(files::add);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not list " + root, e);
            }
        }
        return files;
    }

    private static boolean persistent(String text) {
        return text.contains("@Entity") || text.contains("@Embeddable") || text.contains("@MappedSuperclass")
                || text.contains("persistence.Entity") || text.contains("persistence.Embeddable") || text.contains("persistence.MappedSuperclass");
    }

    private static boolean mentionsQueries(String text) {
        return text.contains("Repository") || text.contains("Query") || text.contains("createQuery");
    }

    private static Finding blame(Finding f, String culprits) {
        return new Finding(f.ruleId(), f.title(), f.category(), f.severity(), f.engine(), f.file(), f.line(), f.column(), f.endLine(),
                f.message() + " This file was not edited: the change to " + culprits + " broke it.", f.remediation(), f.snippet(),
                f.detail(), f.fingerprint());
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
