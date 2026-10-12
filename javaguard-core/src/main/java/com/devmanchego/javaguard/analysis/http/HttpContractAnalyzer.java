package com.devmanchego.javaguard.analysis.http;

import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.devmanchego.javaguard.project.ReactorModules;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JG-API-002..004 (catalog &sect;1.10): the HTTP contract of the changed controllers and DTOs, before the turn against now. An
 * endpoint that no longer exists (a move to another changed file, or to the class-level mapping, keeps it; a renamed handler
 * method does not matter), an endpoint that gains a required parameter or body, a DTO used by an endpoint that loses a JSON
 * property. Advisory: a contract change can be intended, but every existing client breaks.
 */
public final class HttpContractAnalyzer implements DeepAnalyzer {

    public static final RuleInfo API_002 = new RuleInfo("JG-API-002", "HTTP endpoint removed or moved", Category.API_COMPATIBILITY,
            Severity.MAJOR, "diff", "E");
    public static final RuleInfo API_003 = new RuleInfo("JG-API-003", "Endpoint requires a new parameter", Category.API_COMPATIBILITY,
            Severity.MAJOR, "diff", "E");
    public static final RuleInfo API_004 = new RuleInfo("JG-API-004", "JSON field renamed or removed", Category.API_COMPATIBILITY,
            Severity.MAJOR, "diff", "E");
    public static final List<RuleInfo> RULES = List.of(API_002, API_003, API_004);

    private static final int MAX_CONTROLLER_FILES = 500;

    private record Changed(Path file, String relative, CompilationUnit before, CompilationUnit after) {
    }

    @Override
    public List<Finding> analyze(Context context) {
        Map<ReactorModules.Module, List<Changed>> byModule = new LinkedHashMap<>();
        for (Path file : context.changedFiles()) {
            Path absolute = file.toAbsolutePath().normalize();
            if (!absolute.toString().endsWith(".java")) {
                continue;
            }
            Optional<ReactorModules.Module> module = context.reactor().moduleOf(absolute);
            if (module.isEmpty() || !module.get().info().isMainSource(absolute)
                    || module.get().info().frameworks().stream().noneMatch(f -> f == Framework.SPRING_BOOT || f == Framework.SPRING_FRAMEWORK)) {
                continue;
            }
            String before = context.baselineOf().apply(absolute).orElse("");
            String now = read(absolute);
            if (!mentionsContract(before) && !mentionsContract(now)) {
                continue;
            }
            Optional<CompilationUnit> b = before.isEmpty() ? HttpContracts.parse("class Empty {}") : HttpContracts.parse(before);
            Optional<CompilationUnit> a = now.isEmpty() ? HttpContracts.parse("class Empty {}") : HttpContracts.parse(now);
            if (b.isEmpty() || a.isEmpty()) {
                continue; // A file that does not parse is the compile step's to report.
            }
            String rel = context.paths().projectRoot().relativize(absolute).toString().replace('\\', '/');
            byModule.computeIfAbsent(module.get(), m -> new ArrayList<>()).add(new Changed(absolute, rel, b.get(), a.get()));
        }
        List<Finding> out = new ArrayList<>();
        byModule.forEach((module, changed) -> out.addAll(analyzeModule(module, changed)));
        return out;
    }

    private static boolean mentionsContract(String source) {
        return source.contains("Mapping") || source.contains("record ") || source.contains("class ");
    }

    private List<Finding> analyzeModule(ReactorModules.Module module, List<Changed> changed) {
        List<Finding> out = new ArrayList<>();
        Map<String, HttpContracts.Endpoint> after = new LinkedHashMap<>();
        Map<String, List<HttpContracts.Endpoint>> afterByHandler = new LinkedHashMap<>();
        for (Changed c : changed) {
            for (HttpContracts.Endpoint e : HttpContracts.endpoints(c.after())) {
                after.putIfAbsent(e.key(), e);
                afterByHandler.computeIfAbsent(e.handler(), h -> new ArrayList<>()).add(e);
            }
        }
        for (Changed c : changed) {
            for (HttpContracts.Endpoint old : HttpContracts.endpoints(c.before())) {
                HttpContracts.Endpoint now = after.get(old.key());
                if (now == null && old.method().equals("ANY")) {
                    out.add(finding(API_002, c, old.line(), old.key(), old.describe() + " (" + old.handler() + ") accepted every HTTP "
                            + "method and no longer does" + restricted(afterByHandler.get(old.handler())) + ": clients using the others get 405.",
                            "Keep the endpoint as it was, or add the new mapping alongside the old one and deprecate it."));
                } else if (now == null && !after.containsKey("ANY " + old.path())) {
                    String moved = afterByHandler.getOrDefault(old.handler(), List.of()).stream().map(HttpContracts.Endpoint::describe)
                            .findFirst().map(d -> " (" + old.handler() + " now answers " + d + ")").orElse("");
                    out.add(finding(API_002, c, old.line(), old.key(), "Endpoint " + old.describe() + " no longer exists" + moved
                                    + ": existing clients get 404 or 405.",
                            "Keep the old mapping (it can delegate to the new one) until every client has moved, or make the change "
                                    + "on purpose and tell the clients."));
                } else if (now != null) {
                    out.addAll(compare(c, old, now));
                }
            }
        }
        out.addAll(dtoChanges(module, changed));
        return out;
    }

    private static String restricted(List<HttpContracts.Endpoint> now) {
        if (now == null || now.isEmpty()) {
            return "";
        }
        return " (now " + now.stream().map(HttpContracts.Endpoint::method).distinct().collect(Collectors.joining(", ")) + " only)";
    }

    private List<Finding> compare(Changed c, HttpContracts.Endpoint old, HttpContracts.Endpoint now) {
        List<Finding> out = new ArrayList<>();
        Set<String> lostProduces = lost(old.produces(), now.produces());
        Set<String> lostConsumes = lost(old.consumes(), now.consumes());
        if (!lostProduces.isEmpty() || !lostConsumes.isEmpty()) {
            out.add(finding(API_002, c, now.line(), now.key() + "|media", "Endpoint " + now.describe() + " no longer "
                            + (lostProduces.isEmpty() ? "" : "produces " + lostProduces) + (lostProduces.isEmpty() || lostConsumes.isEmpty() ? "" : " and no longer ")
                            + (lostConsumes.isEmpty() ? "" : "consumes " + lostConsumes) + ": clients sending or accepting those media types get 406/415.",
                    "Keep the media types the endpoint already supported."));
        }
        Set<String> oldParams = old.params().stream().filter(HttpContracts.Param::required).map(p -> p.kind() + ":" + p.name())
                .collect(Collectors.toSet());
        for (HttpContracts.Param p : now.params()) {
            if (p.required() && !oldParams.contains(p.kind() + ":" + p.name())) {
                out.add(finding(API_003, c, now.line(), now.key() + "|" + p.kind() + ":" + p.name(), "Endpoint " + now.describe()
                                + " now requires the " + p.kind() + " '" + p.name() + "': existing clients do not send it and get 400.",
                        "Make it optional (required = false, a defaultValue, or Optional) so existing clients keep working."));
            }
        }
        if (now.bodyRequired() && old.bodyType().isEmpty()) {
            out.add(finding(API_003, c, now.line(), now.key() + "|body", "Endpoint " + now.describe()
                            + " now requires a request body: existing clients do not send one and get 400.",
                    "Accept the body as optional (@RequestBody(required = false)) or add a new endpoint."));
        }
        return out;
    }

    private static Set<String> lost(Set<String> before, Set<String> now) {
        if (before.isEmpty() || now.isEmpty()) {
            return Set.of(); // No declaration means every media type.
        }
        Set<String> lost = new TreeSet<>(before);
        lost.removeAll(now);
        return lost;
    }

    /** DTOs declared in changed files and used (as body or return type) by any controller of the module. */
    private List<Finding> dtoChanges(ReactorModules.Module module, List<Changed> changed) {
        Map<String, Changed> declaredIn = new LinkedHashMap<>();
        Map<String, HttpContracts.JsonType> before = new LinkedHashMap<>();
        Map<String, HttpContracts.JsonType> after = new LinkedHashMap<>();
        for (Changed c : changed) {
            HttpContracts.jsonTypes(c.before()).forEach(t -> {
                before.put(t.name(), t);
                declaredIn.put(t.name(), c);
            });
            HttpContracts.jsonTypes(c.after()).forEach(t -> after.put(t.name(), t));
        }
        if (before.isEmpty()) {
            return List.of();
        }
        Set<String> used = usedTypes(module, changed);
        List<Finding> out = new ArrayList<>();
        for (HttpContracts.JsonType old : before.values()) {
            HttpContracts.JsonType now = after.get(old.name());
            if (!used.contains(old.name()) || now == null || !old.known() || !now.known()) {
                continue; // A type that disappeared breaks the compile, which reports it better.
            }
            Set<String> added = new TreeSet<>(now.properties().keySet());
            added.removeAll(old.properties().keySet());
            for (String property : old.properties().keySet()) {
                if (now.properties().containsKey(property)) {
                    continue;
                }
                String renamed = added.size() == 1 ? " (renamed to '" + added.iterator().next() + "'?)" : "";
                Changed c = declaredIn.get(old.name());
                int line = now.properties().values().stream().findFirst().orElse(0);
                out.add(finding(API_004, c, line, old.name() + "." + property, old.name() + " no longer has the JSON property '"
                                + property + "'" + renamed + ": clients reading it get null, clients sending it are ignored "
                                + "(or rejected when unknown properties fail).",
                        "Keep the JSON name with @JsonProperty(\"" + property + "\") on the renamed member, or keep the property."));
            }
        }
        return out;
    }

    private Set<String> usedTypes(ReactorModules.Module module, List<Changed> changed) {
        Set<String> used = new HashSet<>();
        Set<Path> changedFiles = changed.stream().map(Changed::file).collect(Collectors.toSet());
        for (Changed c : changed) {
            Stream.concat(HttpContracts.endpoints(c.before()).stream(), HttpContracts.endpoints(c.after()).stream()).forEach(e -> {
                e.bodyType().ifPresent(used::add);
                used.addAll(e.returnTypes());
            });
        }
        int scanned = 0;
        for (Path root : module.info().mainSourceRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (changedFiles.contains(file.toAbsolutePath().normalize()) || scanned >= MAX_CONTROLLER_FILES) {
                        continue;
                    }
                    String text = read(file);
                    if (!text.contains("Controller")) {
                        continue;
                    }
                    scanned++;
                    HttpContracts.parse(text).ifPresent(u -> HttpContracts.endpoints(u).forEach(e -> {
                        e.bodyType().ifPresent(used::add);
                        used.addAll(e.returnTypes());
                    }));
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable tree contributes nothing.
            }
        }
        return used;
    }

    private static Finding finding(RuleInfo rule, Changed c, int line, String identity, String message, String remediation) {
        return new Finding(rule.ruleId(), rule.title(), rule.category(), rule.defaultSeverity(), rule.engine(), Optional.of(Path.of(c.relative())),
                Math.max(line, 0), 0, Math.max(line, 0), message, remediation, Optional.empty(), Map.of(),
                Fingerprints.of(rule.ruleId(), c.relative(), identity));
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
