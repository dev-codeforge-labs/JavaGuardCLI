package com.devmanchego.javaguard.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring Data's derived-query method names, read the way {@code PartTree} and {@code PropertyPath} (spring-data-commons 3.x)
 * read them, so that a property path Spring would reject at application start-up is rejected here, before the build:
 *
 * <ul>
 *   <li>the name must match {@code ^(find|read|get|query|search|stream|count|exists|delete|remove)(\p{Lu}.*?)??By} (the
 *       subject between the verb and {@code By} - {@code Distinct}, {@code First10}, any word - is irrelevant);</li>
 *   <li>the predicate loses a trailing {@code AllIgnoreCase}/{@code AllIgnoringCase}, is split once on {@code OrderBy},
 *       then on {@code Or} and {@code And} followed by an upper-case letter;</li>
 *   <li>each part loses {@code IgnoreCase}/{@code IgnoringCase}, then the first operator keyword (in Spring's order of
 *       types) it ends with: {@code IsNotNull}, {@code Between}, {@code LessThanEqual}, {@code In}, {@code True}, ...;</li>
 *   <li>what remains is a property path: {@code _} separates properties explicitly; otherwise the whole text is tried as
 *       one property (decapitalized like {@code java.beans.Introspector}), then ever shorter heads ending on a camel-case
 *       boundary, and the first head that exists is committed to, its tail resolved against the property's type;</li>
 *   <li>{@code OrderBy} clauses are split after {@code Asc}/{@code Desc} and each property resolved the same way.</li>
 * </ul>
 *
 * The answer is three-valued: a path is resolved, missing for sure, or unknown (the type or a superclass is outside the
 * module, the entity uses property access, an attribute name has an underscore). Only "missing" becomes a finding.
 */
final class SpringDataDerivedQuery {

    /** One condition: the property path as written, and how many method arguments its operator consumes. */
    record Part(String source, String property, String keyword, int arguments) {
    }

    /** A parsed derived-query method name. */
    record Parsed(List<Part> parts, List<String> orderBy) {
        int requiredArguments() {
            return parts.stream().mapToInt(Part::arguments).sum();
        }
    }

    /** Resolution of one property path. */
    enum Resolution { RESOLVED, MISSING, UNKNOWN }

    private static final Pattern PREFIX = Pattern.compile("^(find|read|get|query|search|stream|count|exists|delete|remove)(\\p{Lu}.*?)??By");
    private static final Pattern ALL_IGNORE_CASE = Pattern.compile("AllIgnor(?:ing|e)Case");
    private static final Pattern IGNORE_CASE = Pattern.compile("Ignor(?:ing|e)Case");
    private static final Pattern LAST_CAMEL_WORD = Pattern.compile("\\p{Lu}\\p{Ll}*$");
    private static final Pattern ORDER_BLOCK = Pattern.compile("(?<=Asc|Desc)(?=\\p{Lu})");
    private static final Pattern ORDER_DIRECTION = Pattern.compile("(.+?)(Asc|Desc)?$");

    /** The CrudRepository/JpaRepository/QueryByExample methods a repository may redeclare: not derived queries. */
    private static final Set<String> BASE_METHODS = Set.of("findById", "existsById", "deleteById", "findAllById", "deleteAllById",
            "deleteAllByIdInBatch", "getById", "getReferenceById", "findBy", "getOne");

    /** Spring's operator types in their matching order, each with its keywords and number of arguments. */
    private record Type(int arguments, List<String> keywords) {
    }

    private static final List<Type> TYPES = List.of(
            new Type(0, List.of("IsNotNull", "NotNull")),
            new Type(0, List.of("IsNull", "Null")),
            new Type(2, List.of("IsBetween", "Between")),
            new Type(1, List.of("IsLessThan", "LessThan")),
            new Type(1, List.of("IsLessThanEqual", "LessThanEqual")),
            new Type(1, List.of("IsGreaterThan", "GreaterThan")),
            new Type(1, List.of("IsGreaterThanEqual", "GreaterThanEqual")),
            new Type(1, List.of("IsBefore", "Before")),
            new Type(1, List.of("IsAfter", "After")),
            new Type(1, List.of("IsNotLike", "NotLike")),
            new Type(1, List.of("IsLike", "Like")),
            new Type(1, List.of("IsStartingWith", "StartingWith", "StartsWith")),
            new Type(1, List.of("IsEndingWith", "EndingWith", "EndsWith")),
            new Type(0, List.of("IsNotEmpty", "NotEmpty")),
            new Type(0, List.of("IsEmpty", "Empty")),
            new Type(1, List.of("IsNotContaining", "NotContaining", "NotContains")),
            new Type(1, List.of("IsContaining", "Containing", "Contains")),
            new Type(1, List.of("IsNotIn", "NotIn")),
            new Type(1, List.of("IsIn", "In")),
            new Type(-1, List.of("IsNear", "Near")),
            new Type(-1, List.of("IsWithin", "Within")),
            new Type(1, List.of("MatchesRegex", "Matches", "Regex")),
            new Type(0, List.of("Exists")),
            new Type(0, List.of("IsTrue", "True")),
            new Type(0, List.of("IsFalse", "False")),
            new Type(1, List.of("IsNot", "Not")),
            new Type(1, List.of("Is", "Equals")));

    /** Value types that a path cannot continue into (Spring would look for a bean property on them and not find one). */
    private static final Set<String> VALUE_TYPES = Set.of("String", "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean",
            "Character", "int", "long", "short", "byte", "double", "float", "boolean", "char", "BigDecimal", "BigInteger", "UUID",
            "LocalDate", "LocalDateTime", "LocalTime", "Instant", "OffsetDateTime", "ZonedDateTime", "Date", "Duration", "Year",
            "YearMonth");

    private SpringDataDerivedQuery() {
    }

    /** Empty when the method name is not a derived query (no verb, no {@code By}, a base repository method). */
    static Optional<Parsed> parse(String methodName) {
        if (BASE_METHODS.contains(methodName)) {
            return Optional.empty();
        }
        Matcher prefix = PREFIX.matcher(methodName);
        if (!prefix.find()) {
            return Optional.empty();
        }
        String predicate = methodName.substring(prefix.end());
        if (predicate.isEmpty()) {
            return Optional.empty();
        }
        predicate = ALL_IGNORE_CASE.matcher(predicate).replaceFirst("");
        String[] ordered = predicate.split("OrderBy", -1);
        if (ordered.length > 2) {
            return Optional.empty(); // Spring rejects this itself, with its own message; not a property problem.
        }
        List<Part> parts = new ArrayList<>();
        if (!ordered[0].isEmpty()) {
            for (String orPart : ordered[0].split("Or(?=\\p{Lu})", -1)) {
                for (String andPart : orPart.split("And(?=\\p{Lu})", -1)) {
                    if (andPart.isEmpty()) {
                        return Optional.empty();
                    }
                    parts.add(part(andPart));
                }
            }
        }
        List<String> orderBy = new ArrayList<>();
        if (ordered.length == 2) {
            for (String block : ORDER_BLOCK.split(ordered[1])) {
                Matcher direction = ORDER_DIRECTION.matcher(block);
                if (direction.matches()) {
                    orderBy.add(direction.group(1));
                }
            }
        }
        return Optional.of(new Parsed(parts, orderBy));
    }

    private static Part part(String source) {
        String raw = IGNORE_CASE.matcher(source).replaceFirst("");
        for (Type type : TYPES) {
            for (String keyword : type.keywords()) {
                if (raw.endsWith(keyword) && raw.length() > keyword.length()) {
                    return new Part(source, raw.substring(0, raw.length() - keyword.length()), keyword, type.arguments());
                }
            }
        }
        return new Part(source, raw, "", 1);
    }

    /**
     * Resolves a path such as {@code AddressZipCode} or {@code Address_ZipCode} from {@code type}, as Spring's
     * {@code PropertyPath.from}: the text is split on {@code _}, and each segment is resolved from the previous one's type
     * with the camel-case algorithm of {@code PropertyPath.create}.
     */
    static Resolution resolve(String path, String type, ModuleModel model) {
        String current = type;
        for (String segment : path.split("_", -1)) {
            if (segment.isEmpty()) {
                return Resolution.UNKNOWN;
            }
            Step step = resolveCamel(segment, "", current, model, 0);
            if (step.resolution() != Resolution.RESOLVED) {
                return step.resolution();
            }
            current = step.type();
        }
        return Resolution.RESOLVED;
    }

    /** A resolution and, when resolved, the type the path ends on. */
    private record Step(Resolution resolution, String type) {
    }

    /** Spring's {@code PropertyPath.create}: whole text first, then ever shorter heads; the first head found commits. */
    private static Step resolveCamel(String head, String tail, String type, ModuleModel model, int depth) {
        if (depth > 50) {
            return new Step(Resolution.UNKNOWN, "");
        }
        ModuleModel.Lookup lookup = lookup(model, type, decapitalize(head));
        if (!lookup.known()) {
            return new Step(Resolution.UNKNOWN, "");
        }
        if (lookup.attribute().isPresent()) {
            String next = lookup.attribute().get().type();
            return tail.isEmpty() ? new Step(Resolution.RESOLVED, next) : resolveCamel(tail, "", next, model, depth + 1);
        }
        Matcher last = LAST_CAMEL_WORD.matcher(head);
        if (last.find() && last.start() != 0) {
            return resolveCamel(head.substring(0, last.start()), head.substring(last.start()) + tail, type, model, depth + 1);
        }
        return new Step(Resolution.MISSING, "");
    }

    private static ModuleModel.Lookup lookup(ModuleModel model, String type, String name) {
        if (VALUE_TYPES.contains(type)) {
            return ModuleModel.Lookup.missingFor(name);
        }
        Optional<ModuleModel.Persistent> persistent = model.persistent(type);
        if (persistent.isPresent() && persistent.get().attributes().keySet().stream().anyMatch(a -> a.contains("_"))) {
            return ModuleModel.Lookup.unknownFor(name); // Spring's own underscore handling is not modelled.
        }
        return model.lookup(type, name);
    }

    /** {@code java.beans.Introspector.decapitalize}: {@code Name} becomes {@code name}, {@code URL} stays {@code URL}. */
    static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return ModuleModel.uncapitalize(name);
    }
}
