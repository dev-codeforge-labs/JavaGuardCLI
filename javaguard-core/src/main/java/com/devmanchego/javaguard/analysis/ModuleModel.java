package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the module's own sources say about its persistent model and a few other types, as seen by syntax alone: the
 * entities, embeddables and mapped superclasses with their persistent attributes, the classes carrying Bean Validation
 * constraints, and who extends whom. Rules that need to look beyond the file they check (JG-SPR-003, -010, -011, -014,
 * -015, -016) ask this through {@link EntityIndex}; everything they cannot see for certain is {@link Lookup#unknown()},
 * never a finding.
 */
public final class ModuleModel {

    /** The kind of a persistent type. */
    public enum Kind { ENTITY, EMBEDDABLE, MAPPED_SUPERCLASS }

    /**
     * One persistent attribute.
     *
     * @param type       simple name of its type; for a collection or map, of the element (map: value) type
     * @param collection a to-many attribute (collection, map or array)
     * @param toMany     annotated {@code @OneToMany}/{@code @ManyToMany}
     */
    public record Attribute(String name, String type, boolean collection, boolean toMany) {
    }

    /**
     * A persistent type.
     *
     * @param jpqlName   the entity name used in JPQL ({@code @Entity(name = ...)}, else the simple name)
     * @param superclass the simple name of the class it extends, if any
     * @param complete   false when its attributes cannot all be known from its fields ({@code @Access(PROPERTY)})
     */
    public record Persistent(String simpleName, String jpqlName, Kind kind, Map<String, Attribute> attributes,
                             Optional<String> superclass, boolean complete) {
        public Persistent {
            attributes = Map.copyOf(attributes);
        }
    }

    /** The answer to "does this type have this attribute": found (with the attribute), missing for sure, or unknown. */
    public record Lookup(Optional<Attribute> attribute, boolean known) {
        static Lookup found(Attribute a) {
            return new Lookup(Optional.of(a), true);
        }

        static Lookup missing() {
            return new Lookup(Optional.empty(), true);
        }

        static Lookup unknown() {
            return new Lookup(Optional.empty(), false);
        }

        /** {@code name} on a value type ({@code String}, {@code LocalDate}...): missing, except bean-style getters such types have. */
        static Lookup missingFor(String name) {
            return Set.of("empty", "blank", "bytes", "class").contains(name) ? unknown() : missing();
        }

        static Lookup unknownFor(String name) {
            return unknown();
        }

        public boolean isMissing() {
            return known && attribute.isEmpty();
        }
    }

    static final Set<String> CONSTRAINTS = Set.of("NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max", "Email", "Pattern",
            "Positive", "PositiveOrZero", "Negative", "NegativeOrZero", "Past", "PastOrPresent", "Future", "FutureOrPresent",
            "Digits", "DecimalMin", "DecimalMax", "AssertTrue", "AssertFalse", "Null");
    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Collection", "SortedSet", "NavigableSet", "Iterable",
            "Map", "SortedMap", "NavigableMap", "ArrayList", "HashSet", "LinkedHashSet", "TreeSet", "LinkedList", "HashMap",
            "LinkedHashMap", "TreeMap");
    private static final Pattern EXTENDS = Pattern.compile("\\b(?:class|record)\\s+(\\w+)\\s*(?:<[^{]*?>)?\\s+extends\\s+([\\w.]+)");

    static final ModuleModel EMPTY = new ModuleModel(Map.of(), Set.of(), Map.of());

    private final Map<String, Persistent> persistent;
    private final Set<String> validatedTypes;
    private final Map<String, String> superclassOf;

    ModuleModel(Map<String, Persistent> persistent, Set<String> validatedTypes, Map<String, String> superclassOf) {
        this.persistent = Map.copyOf(persistent);
        this.validatedTypes = Set.copyOf(validatedTypes);
        this.superclassOf = Map.copyOf(superclassOf);
    }

    // --- queries ---------------------------------------------------------------------------------

    public Optional<Persistent> persistent(String simpleName) {
        return Optional.ofNullable(persistent.get(simpleName));
    }

    public boolean isEntity(String simpleName) {
        return persistent(simpleName).filter(p -> p.kind() == Kind.ENTITY).isPresent();
    }

    /** The entity whose JPQL name is {@code name}. */
    public Optional<Persistent> entityByJpqlName(String name) {
        return persistent.values().stream().filter(p -> p.kind() == Kind.ENTITY && p.jpqlName().equals(name)).findFirst();
    }

    public Set<String> entityNames() {
        Set<String> names = new HashSet<>();
        persistent.values().stream().filter(p -> p.kind() == Kind.ENTITY).forEach(p -> names.add(p.simpleName()));
        return names;
    }

    public Set<String> entityJpqlNames() {
        Set<String> names = new HashSet<>();
        persistent.values().stream().filter(p -> p.kind() == Kind.ENTITY).forEach(p -> names.add(p.jpqlName()));
        return names;
    }

    /** {@code type.name}, following the superclass chain; unknown when the chain leaves the module or the type is incomplete. */
    public Lookup lookup(String type, String name) {
        String current = type;
        for (int depth = 0; depth < 20 && current != null; depth++) {
            Persistent p = persistent.get(current);
            if (p == null) {
                return Lookup.unknown();
            }
            Attribute a = p.attributes().get(name);
            if (a != null) {
                return Lookup.found(a);
            }
            if (!p.complete()) {
                return Lookup.unknown();
            }
            current = p.superclass().orElse(null);
        }
        return Lookup.missing();
    }

    public boolean isValidated(String simpleName) {
        return validatedTypes.contains(simpleName);
    }

    /** The superclasses of {@code simpleName} declared in the module, nearest first. */
    public List<String> superclasses(String simpleName) {
        List<String> chain = new java.util.ArrayList<>();
        String current = superclassOf.get(simpleName);
        while (current != null && chain.size() < 20 && !chain.contains(current)) {
            chain.add(current);
            current = superclassOf.get(current);
        }
        return chain;
    }

    /** Getter names of an entity's {@code @OneToMany}/{@code @ManyToMany} attributes (JG-SPR-003). */
    public Set<String> toManyGetters(String simpleName) {
        Set<String> getters = new HashSet<>();
        persistent(simpleName).ifPresent(p -> p.attributes().values().stream().filter(Attribute::toMany)
                .forEach(a -> getters.add("get" + capitalize(a.name()))));
        return getters;
    }

    /** This model with {@code other}'s declarations taking precedence (the file being checked over the module on disk). */
    public ModuleModel overlay(ModuleModel other) {
        Map<String, Persistent> p = new HashMap<>(persistent);
        p.putAll(other.persistent);
        Set<String> v = new HashSet<>(validatedTypes);
        v.addAll(other.validatedTypes);
        Map<String, String> s = new HashMap<>(superclassOf);
        s.putAll(other.superclassOf);
        return new ModuleModel(p, v, s);
    }

    // --- building --------------------------------------------------------------------------------

    /** The declarations of one parsed file. */
    public static ModuleModel of(CompilationUnit unit) {
        Builder builder = new Builder();
        builder.add(unit, unit.toString());
        return builder.build();
    }

    /** Accumulates files; {@link #hierarchy(String)} takes any source text (cheap), {@link #add} a parsed one. */
    static final class Builder {
        private final Map<String, Persistent> persistent = new HashMap<>();
        private final Set<String> validated = new HashSet<>();
        private final Map<String, String> superclassOf = new HashMap<>();

        void hierarchy(String text) {
            Matcher m = EXTENDS.matcher(text);
            while (m.find()) {
                superclassOf.putIfAbsent(m.group(1), AstSupport.simpleName(m.group(2)));
            }
        }

        void add(CompilationUnit unit, String text) {
            hierarchy(text);
            boolean validationImported = text.contains("validation.constraints") || text.contains("jakarta.validation")
                    || text.contains("javax.validation");
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                if (type instanceof ClassOrInterfaceDeclaration c && !c.isInterface()) {
                    kindOf(c).ifPresent(kind -> persistent.put(c.getNameAsString(), persistentOf(c, kind)));
                    c.getExtendedTypes().stream().findFirst()
                            .ifPresent(e -> superclassOf.put(c.getNameAsString(), e.getNameAsString()));
                }
                if (validationImported && hasConstraints(type)) {
                    validated.add(type.getNameAsString());
                }
            }
        }

        ModuleModel build() {
            return new ModuleModel(persistent, validated, superclassOf);
        }
    }

    private static Optional<Kind> kindOf(ClassOrInterfaceDeclaration c) {
        if (AstSupport.hasAnnotation(c, "Entity")) {
            return Optional.of(Kind.ENTITY);
        }
        if (AstSupport.hasAnnotation(c, "Embeddable")) {
            return Optional.of(Kind.EMBEDDABLE);
        }
        if (AstSupport.hasAnnotation(c, "MappedSuperclass")) {
            return Optional.of(Kind.MAPPED_SUPERCLASS);
        }
        return Optional.empty();
    }

    private static Persistent persistentOf(ClassOrInterfaceDeclaration c, Kind kind) {
        Map<String, Attribute> attributes = new LinkedHashMap<>();
        for (FieldDeclaration field : c.getFields()) {
            if (field.isStatic() || field.hasModifier(Modifier.Keyword.TRANSIENT) || AstSupport.hasAnnotation(field, "Transient")) {
                continue;
            }
            boolean toMany = AstSupport.hasAnnotation(field, "OneToMany", "ManyToMany");
            for (VariableDeclarator v : field.getVariables()) {
                Type type = v.getType();
                attributes.put(v.getNameAsString(), new Attribute(v.getNameAsString(), elementType(type), isCollection(type), toMany));
            }
        }
        String jpqlName = c.getAnnotationByName("Entity").flatMap(ModuleModel::nameMember).orElse(c.getNameAsString());
        boolean complete = c.getAnnotationByName("Access").map(a -> !a.toString().contains("PROPERTY")).orElse(true);
        Optional<String> superclass = c.getExtendedTypes().stream().findFirst().map(ClassOrInterfaceType::getNameAsString);
        return new Persistent(c.getNameAsString(), jpqlName, kind, attributes, superclass, complete);
    }

    private static Optional<String> nameMember(AnnotationExpr annotation) {
        if (annotation.isNormalAnnotationExpr()) {
            return annotation.asNormalAnnotationExpr().getPairs().stream().filter(p -> p.getNameAsString().equals("name"))
                    .map(p -> p.getValue()).filter(v -> v.isStringLiteralExpr()).map(v -> v.asStringLiteralExpr().getValue()).findFirst();
        }
        return Optional.empty();
    }

    private static boolean isCollection(Type type) {
        return type.isArrayType() || type.isClassOrInterfaceType() && COLLECTIONS.contains(type.asClassOrInterfaceType().getNameAsString());
    }

    private static String elementType(Type type) {
        if (type.isArrayType()) {
            return elementType(type.asArrayType().getComponentType());
        }
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType c = type.asClassOrInterfaceType();
            if (COLLECTIONS.contains(c.getNameAsString()) && c.getTypeArguments().filter(args -> !args.isEmpty()).isPresent()) {
                var args = c.getTypeArguments().get();
                return elementType(args.get(args.size() - 1));
            }
            return c.getNameAsString();
        }
        return type.asString();
    }

    private static boolean hasConstraints(TypeDeclaration<?> type) {
        if (type instanceof RecordDeclaration record) {
            return record.getParameters().stream().anyMatch(p -> isConstrained(p));
        }
        return type.getFields().stream().anyMatch(ModuleModel::isConstrained)
                || type.getMethods().stream().anyMatch(ModuleModel::isConstrained);
    }

    private static boolean isConstrained(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().anyMatch(a -> CONSTRAINTS.contains(AstSupport.simpleName(a.getNameAsString())));
    }

    static String capitalize(String name) {
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    static String uncapitalize(String name) {
        return name.isEmpty() ? name : name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }
}
