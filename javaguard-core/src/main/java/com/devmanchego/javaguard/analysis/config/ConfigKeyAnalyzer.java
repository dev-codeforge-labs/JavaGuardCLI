package com.devmanchego.javaguard.analysis.config;

import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.devmanchego.javaguard.project.ReactorModules;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JG-CFG-002 (catalog &sect;1.13): a configuration key the code requires that no configuration file of the module defines:
 * a {@code ${key}} without default in {@code @Value}, or a {@code @NotNull}/{@code @NotBlank}/{@code @NotEmpty} property of
 * a {@code @Validated @ConfigurationProperties} class. The application fails to start unless the environment provides the
 * key, which is why this is advisory. Keys are compared after Spring's relaxed binding ({@link ConfigFiles#canonical}); a
 * key is also defined when it is the prefix of a defined one, and keys defined by any module of the build count (a library
 * module is configured by the application that packages it). Modules that import configuration from elsewhere
 * ({@code spring.config.import}, Spring Cloud) are not judged, nor are environment-style names ({@code ${PORT}}) and JVM
 * system properties. Only problems the turn introduced are reported: the same analysis runs on the module as it was
 * before the turn (the changed files' baselines) and its results are subtracted.
 */
public final class ConfigKeyAnalyzer implements DeepAnalyzer {

    public static final RuleInfo INFO = new RuleInfo("JG-CFG-002", "Configuration key never defined", Category.CONFIGURATION,
            Severity.MAJOR, "config", "E");

    private static final int MAX_FILES = 1000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(:)?");
    private static final Set<String> SYSTEM_PREFIXES = Set.of("java.", "user.", "os.", "file.", "line.", "path.", "random.",
            "local.", "jdk.", "sun.", "spring.application.pid");
    private static final Set<String> REQUIRED = Set.of("NotNull", "NotBlank", "NotEmpty");

    /** A key the code needs, where it needs it. */
    private record Usage(Path file, int line, String key, String how) {
        String identity() {
            return file + "|" + key;
        }
    }

    @Override
    public List<Finding> analyze(Context context) {
        Map<ReactorModules.Module, Map<Path, String>> modules = new LinkedHashMap<>();
        for (Path file : context.changedFiles()) {
            Path absolute = file.toAbsolutePath().normalize();
            String name = absolute.getFileName().toString();
            boolean javaMain = name.endsWith(".java");
            boolean config = ConfigFiles.isConfigFile(name) && !ConfigFiles.isTestResource(absolute.toString());
            if (!javaMain && !config) {
                continue;
            }
            Optional<ReactorModules.Module> module = context.reactor().moduleOf(absolute);
            if (module.isEmpty() || !module.get().info().frameworks().contains(Framework.SPRING_BOOT)
                    || javaMain && !module.get().info().isMainSource(absolute)) {
                continue;
            }
            modules.computeIfAbsent(module.get(), m -> new HashMap<>()).put(absolute, context.baselineOf().apply(absolute).orElse(""));
        }
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<ReactorModules.Module, Map<Path, String>> entry : modules.entrySet()) {
            out.addAll(analyzeModule(context, entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private List<Finding> analyzeModule(Context context, ReactorModules.Module module, Map<Path, String> baselines) {
        List<Path> javaFiles = files(module.info().mainSourceRoots(), p -> p.toString().endsWith(".java"));
        // A library module is configured by the application module that packages it: keys defined anywhere in the build count.
        java.util.Set<Path> all = new java.util.LinkedHashSet<>(configFiles(module.info().moduleRoot()));
        context.reactor().all().forEach(m -> all.addAll(configFiles(m.info().moduleRoot())));
        List<Path> configFiles = List.copyOf(all);
        Map<Path, String> none = Map.of();
        Optional<List<Usage>> now = unresolved(javaFiles, configFiles, none);
        if (now.isEmpty() || now.get().isEmpty()) {
            return List.of();
        }
        Set<String> before = unresolved(javaFiles, configFiles, baselines)
                .map(list -> list.stream().map(Usage::identity).collect(java.util.stream.Collectors.toSet())).orElse(Set.of());
        List<Finding> out = new ArrayList<>();
        for (Usage usage : now.get()) {
            if (!before.contains(usage.identity())) {
                out.add(finding(context, usage));
            }
        }
        return out;
    }

    /** Usages no configuration file defines; empty when the module takes configuration from elsewhere. */
    private Optional<List<Usage>> unresolved(List<Path> currentJava, List<Path> currentConfig, Map<Path, String> overrides) {
        // Files deleted in the turn exist only as baselines: they are part of the module as it was.
        Set<Path> javaFiles = new java.util.LinkedHashSet<>(currentJava);
        Set<Path> configFiles = new java.util.LinkedHashSet<>(currentConfig);
        overrides.keySet().forEach(p -> (p.toString().endsWith(".java") ? javaFiles : configFiles).add(p));
        Set<String> defined = new HashSet<>();
        for (Path file : configFiles) {
            for (ConfigFiles.Entry entry : ConfigFiles.parse(file.getFileName().toString(), text(file, overrides))) {
                if (entry.key().startsWith("spring.config.import") || entry.key().startsWith("spring.cloud.")) {
                    return Optional.empty();
                }
                String canonical = ConfigFiles.canonical(entry.key());
                defined.add(canonical);
                String[] parts = entry.key().split("\\.");
                StringBuilder prefix = new StringBuilder();
                for (String part : parts) {
                    prefix.append(part).append('.');
                    defined.add(ConfigFiles.canonical(prefix.toString()));
                }
            }
        }
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        List<Usage> usages = new ArrayList<>();
        int parsed = 0;
        for (Path file : javaFiles) {
            String text = text(file, overrides);
            if (!text.contains("@Value") && !text.contains("ConfigurationProperties")) {
                continue;
            }
            if (text.contains("@PropertySource") || text.contains("@TestPropertySource")) {
                return Optional.empty(); // Keys may come from files this analysis does not resolve.
            }
            if (parsed++ > MAX_FILES) {
                break;
            }
            Optional<CompilationUnit> unit = parser.parse(text).getResult();
            unit.ifPresent(u -> collect(file, u, usages));
        }
        return Optional.of(usages.stream().filter(u -> !defined.contains(ConfigFiles.canonical(u.key()))).toList());
    }

    private static void collect(Path file, CompilationUnit unit, List<Usage> out) {
        for (AnnotationExpr annotation : unit.findAll(AnnotationExpr.class)) {
            if (!simple(annotation.getNameAsString()).equals("Value")) {
                continue;
            }
            value(annotation).flatMap(ConfigKeyAnalyzer::literal).ifPresent(text -> {
                Matcher m = PLACEHOLDER.matcher(text);
                while (m.find()) {
                    String key = m.group(1).strip();
                    if (m.group(2) == null && judged(key)) {
                        out.add(new Usage(file, line(annotation), key, "@Value(\"${" + key + "}\") has no default"));
                    }
                }
            });
        }
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            Optional<AnnotationExpr> properties = type.getAnnotationByName("ConfigurationProperties");
            if (properties.isEmpty() || !type.isAnnotationPresent("Validated")) {
                continue;
            }
            Optional<String> prefix = prefix(properties.get());
            if (prefix.isEmpty()) {
                continue;
            }
            List<NodeWithAnnotations<?>> members = new ArrayList<>();
            if (type instanceof RecordDeclaration record) {
                members.addAll(record.getParameters());
            } else if (type instanceof ClassOrInterfaceDeclaration c) {
                c.getFields().stream().filter(f -> !f.isStatic()).forEach(members::add);
            }
            for (NodeWithAnnotations<?> member : members) {
                if (member.getAnnotations().stream().noneMatch(a -> REQUIRED.contains(simple(a.getNameAsString())))) {
                    continue;
                }
                for (String name : names(member)) {
                    String key = prefix.get() + "." + name;
                    out.add(new Usage(file, line((Node) member), key, "@ConfigurationProperties property " + key + " is required"));
                }
            }
        }
    }

    private static List<String> names(NodeWithAnnotations<?> member) {
        if (member instanceof Parameter p) {
            return List.of(p.getNameAsString());
        }
        if (member instanceof FieldDeclaration f) {
            return f.getVariables().stream().map(v -> v.getNameAsString()).toList();
        }
        return List.of();
    }

    private static boolean judged(String key) {
        if (key.isEmpty() || key.startsWith("#") || !key.contains(".")) {
            return false; // ${PORT}, ${HOME}: environment variables; SpEL is not configuration.
        }
        return SYSTEM_PREFIXES.stream().noneMatch(key::startsWith);
    }

    private static Optional<String> prefix(AnnotationExpr annotation) {
        Optional<Expression> value = Optional.empty();
        if (annotation.isSingleMemberAnnotationExpr()) {
            value = Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        } else if (annotation.isNormalAnnotationExpr()) {
            value = annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("prefix") || p.getNameAsString().equals("value"))
                    .map(MemberValuePair::getValue).findFirst();
        }
        return value.flatMap(ConfigKeyAnalyzer::literal).filter(p -> !p.isBlank());
    }

    private static Optional<Expression> value(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (annotation.isNormalAnnotationExpr()) {
            return annotation.asNormalAnnotationExpr().getPairs().stream().filter(p -> p.getNameAsString().equals("value"))
                    .map(MemberValuePair::getValue).findFirst();
        }
        return Optional.empty();
    }

    private static Optional<String> literal(Expression e) {
        if (e.isStringLiteralExpr()) {
            return Optional.of(e.asStringLiteralExpr().asString());
        }
        if (e.isBinaryExpr()) {
            Optional<String> left = literal(e.asBinaryExpr().getLeft());
            Optional<String> right = literal(e.asBinaryExpr().getRight());
            return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
        }
        return Optional.empty();
    }

    private static String simple(String name) {
        return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    /** {@code application*} and {@code bootstrap*} files Spring Boot loads: main resources, {@code ./} and {@code ./config/}. */
    private static List<Path> configFiles(Path moduleRoot) {
        List<Path> roots = List.of(moduleRoot.resolve("src/main/resources"), moduleRoot.resolve("config"));
        List<Path> out = new ArrayList<>(files(roots, p -> ConfigFiles.isConfigFile(p.getFileName().toString())));
        try (Stream<Path> top = Files.list(moduleRoot)) {
            top.filter(p -> Files.isRegularFile(p) && ConfigFiles.isConfigFile(p.getFileName().toString())).forEach(out::add);
        } catch (IOException | RuntimeException e) {
            // No top-level files: nothing to add.
        }
        return out;
    }

    private static List<Path> files(List<Path> roots, java.util.function.Predicate<Path> filter) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).filter(filter).map(p -> p.toAbsolutePath().normalize()).sorted().forEach(out::add);
            } catch (IOException | RuntimeException e) {
                // An unreadable tree contributes nothing.
            }
        }
        return out;
    }

    private static String text(Path file, Map<Path, String> overrides) {
        if (overrides.containsKey(file)) {
            return overrides.get(file);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static Finding finding(Context context, Usage usage) {
        String rel = context.paths().projectRoot().relativize(usage.file()).toString().replace('\\', '/');
        return new Finding(INFO.ruleId(), INFO.title(), INFO.category(), INFO.defaultSeverity(), INFO.engine(), Optional.of(Path.of(rel)),
                usage.line(), 0, usage.line(), usage.how() + ", and no application*.properties/yml of this module defines '"
                        + usage.key() + "': the application fails to start unless the environment provides it.",
                "Define " + usage.key() + " in application.yml (per profile if it differs), or give the placeholder a default "
                        + "(${" + usage.key() + ":...}) if it is optional.",
                Optional.empty(), Map.of("key", usage.key()), Fingerprints.of(INFO.ruleId(), rel, usage.key()));
    }
}
