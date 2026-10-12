package com.devmanchego.javaguard.analysis;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small JPQL reader for JG-SPR-016: it finds the identification variables a query declares
 * ({@code FROM Entity e}, {@code , Entity e}, {@code JOIN [FETCH] e.path p}, {@code JOIN Entity p}) and checks every
 * {@code alias.attribute.attribute} path against the module model. String literals are blanked first. Anything it does
 * not understand - an entity outside the module, {@code TREAT}, a path through a collection, the special {@code id} - is
 * unknown and never reported. The only entity-name problems reported are a class's simple name used where its
 * {@code @Entity(name = ...)} is required, and a name one or two letters away from a known entity's.
 */
final class JpqlChecker {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern DECLARATION = Pattern.compile(
            "(?i)\\b(from|update|join(?:\\s+fetch)?)\\s+([A-Za-z_$][\\w$.]*)\\s+(?:as\\s+)?([A-Za-z_$][\\w$]*)");
    private static final Pattern MORE_ROOTS = Pattern.compile("(?i),\\s*([A-Z][\\w$.]*)\\s+(?:as\\s+)?([A-Za-z_$][\\w$]*)");
    private static final Set<String> KEYWORDS = Set.of("where", "join", "left", "right", "inner", "outer", "cross", "order", "group",
            "set", "on", "with", "fetch", "having", "union", "select", "and", "or", "full", "limit", "offset", "intersect", "except");

    private JpqlChecker() {
    }

    /** The first problem found, if any. */
    static Optional<String> check(String jpql, ModuleModel model) {
        return check(jpql, model, Set.of());
    }

    /**
     * @param existingTypes simple names of types the file refers to: such a name is a real class (possibly an entity of
     *                      another module) and is never reported as a misspelled entity
     */
    static Optional<String> check(String jpql, ModuleModel model, Set<String> existingTypes) {
        String text = STRING_LITERAL.matcher(jpql).replaceAll("''");
        if (text.toLowerCase(Locale.ROOT).contains("treat(")) {
            return Optional.empty();
        }
        Map<String, String> aliases = new HashMap<>(); // lower-case alias -> persistent type simple name ("" = unknown)
        Matcher declaration = DECLARATION.matcher(text);
        while (declaration.find()) {
            String keyword = declaration.group(1).toLowerCase(Locale.ROOT);
            String target = declaration.group(2);
            String alias = declaration.group(3);
            if (KEYWORDS.contains(alias.toLowerCase(Locale.ROOT))) {
                continue; // HQL without an alias: nothing to resolve paths against.
            }
            if (!keyword.startsWith("join") && target.contains(".")
                    && aliases.containsKey(target.substring(0, target.indexOf('.')).toLowerCase(Locale.ROOT))) {
                continue; // "extract(year from e.date)", "trim(... from e.name)": a function argument, not a declaration.
            }
            if (keyword.startsWith("join") && target.contains(".")) {
                aliases.put(alias.toLowerCase(Locale.ROOT), joinTarget(target, aliases, model));
                continue;
            }
            Optional<String> problem = entityProblem(target, model, existingTypes);
            if (problem.isPresent()) {
                return problem;
            }
            aliases.put(alias.toLowerCase(Locale.ROOT), entity(target, model).orElse(""));
        }
        Matcher more = MORE_ROOTS.matcher(text);
        while (more.find()) {
            if (KEYWORDS.contains(more.group(2).toLowerCase(Locale.ROOT))) {
                continue;
            }
            Optional<String> problem = entityProblem(more.group(1), model, existingTypes);
            if (problem.isPresent()) {
                return problem;
            }
            aliases.putIfAbsent(more.group(2).toLowerCase(Locale.ROOT), entity(more.group(1), model).orElse(""));
        }
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (alias.getValue().isEmpty()) {
                continue;
            }
            Pattern path = Pattern.compile("(?i)(?<![\\w$.])" + Pattern.quote(alias.getKey()) + "\\.([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)");
            Matcher m = path.matcher(text);
            while (m.find()) {
                Optional<String> problem = pathProblem(alias.getValue(), m.group(1), model);
                if (problem.isPresent()) {
                    return problem;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> entity(String name, ModuleModel model) {
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return model.entityByJpqlName(simple).map(ModuleModel.Persistent::simpleName);
    }

    private static Optional<String> entityProblem(String name, ModuleModel model, Set<String> existingTypes) {
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        if (model.entityByJpqlName(simple).isPresent()) {
            return Optional.empty();
        }
        Optional<ModuleModel.Persistent> renamed = model.persistent(simple).filter(p -> p.kind() == ModuleModel.Kind.ENTITY);
        if (renamed.isPresent()) {
            return Optional.of(simple + " is mapped with @Entity(name = \"" + renamed.get().jpqlName() + "\"); JPQL must use "
                    + renamed.get().jpqlName());
        }
        if (simple.length() >= 4 && !existingTypes.contains(simple)) {
            for (String known : model.entityJpqlNames()) {
                if (distance(simple, known) <= 2) {
                    return Optional.of("no entity is named " + simple + " (did you mean " + known + "?)");
                }
            }
        }
        return Optional.empty();
    }

    /** {@code e.orders}: the element type of the joined attribute, or unknown. */
    private static String joinTarget(String path, Map<String, String> aliases, ModuleModel model) {
        String[] segments = path.split("\\.");
        String type = aliases.getOrDefault(segments[0].toLowerCase(Locale.ROOT), "");
        for (int i = 1; i < segments.length && !type.isEmpty(); i++) {
            ModuleModel.Lookup lookup = model.lookup(type, segments[i]);
            type = lookup.attribute().map(ModuleModel.Attribute::type).orElse("");
        }
        return type;
    }

    private static Optional<String> pathProblem(String type, String path, ModuleModel model) {
        String current = type;
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            ModuleModel.Lookup lookup = model.lookup(current, segment);
            if (!lookup.known() || segment.equals("id") && lookup.attribute().isEmpty()) {
                return Optional.empty();
            }
            if (lookup.attribute().isEmpty()) {
                return Optional.of(current + " has no attribute '" + segment + "'" + suggestion(current, segment, model));
            }
            ModuleModel.Attribute attribute = lookup.attribute().get();
            if (attribute.collection() && i + 1 < segments.length) {
                return Optional.empty(); // Dereferencing a collection: the provider decides; not judged here.
            }
            current = attribute.type();
        }
        return Optional.empty();
    }

    static String suggestion(String type, String name, ModuleModel model) {
        return model.persistent(type).flatMap(p -> p.attributes().keySet().stream()
                        .filter(a -> distance(a.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)) <= 2).findFirst())
                .map(a -> " (did you mean " + a + "?)").orElse("");
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
