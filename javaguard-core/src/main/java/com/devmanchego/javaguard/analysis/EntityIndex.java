package com.devmanchego.javaguard.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The module's {@link ModuleModel}, built lazily, once, only when a rule asks, from the module's main sources: every
 * file is read (for the class hierarchy, a cheap regular expression), and only the files that can declare a persistent
 * type or a Bean Validation constraint are parsed. Without symbol resolution a simple-name match is the best available
 * answer; rules treat anything uncertain as unknown.
 */
public final class EntityIndex {

    private static final Logger LOG = Logger.getLogger(EntityIndex.class.getName());

    private final Supplier<ModuleModel> loader;
    private volatile ModuleModel loaded;

    private EntityIndex(Supplier<ModuleModel> loader) {
        this.loader = loader;
    }

    public static EntityIndex empty() {
        return new EntityIndex(() -> ModuleModel.EMPTY);
    }

    /** An index over the {@code .java} files under {@code sourceRoots}, read on first use. */
    public static EntityIndex scanning(List<Path> sourceRoots) {
        return new EntityIndex(() -> scan(sourceRoots));
    }

    /**
     * An index over {@code sourceRoots} where some files are read from {@code contents} instead of disk (an absent file
     * that maps to text is added; a file that maps to {@code ""} is left out): the module as it was before a change.
     */
    static EntityIndex scanning(List<Path> sourceRoots, Map<Path, String> contents) {
        return new EntityIndex(() -> scan(sourceRoots, contents));
    }

    public ModuleModel model() {
        ModuleModel result = loaded;
        if (result == null) {
            synchronized (this) {
                result = loaded;
                if (result == null) {
                    result = loader.get();
                    loaded = result;
                }
            }
        }
        return result;
    }

    public boolean isEntity(String simpleName) {
        return model().isEntity(simpleName);
    }

    /** Getter names of the entity's {@code @OneToMany}/{@code @ManyToMany} fields, e.g. {@code getOrders}. */
    public Set<String> toManyGetters(String entitySimpleName) {
        return model().toManyGetters(entitySimpleName);
    }

    /** The entities declared in one file: simple name to to-many getter names. */
    static Map<String, Set<String>> entitiesIn(CompilationUnit unit) {
        ModuleModel model = ModuleModel.of(unit);
        Map<String, Set<String>> found = new java.util.HashMap<>();
        model.entityNames().forEach(name -> found.put(name, model.toManyGetters(name)));
        return found;
    }

    private static ModuleModel scan(List<Path> sourceRoots) {
        return scan(sourceRoots, Map.of());
    }

    private static ModuleModel scan(List<Path> sourceRoots, Map<Path, String> contents) {
        ModuleModel.Builder builder = new ModuleModel.Builder();
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21).setAttributeComments(false));
        Set<Path> seen = new java.util.HashSet<>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Path key = file.toAbsolutePath().normalize();
                    seen.add(key);
                    String text = contents.containsKey(key) ? contents.get(key) : Files.readString(file, StandardCharsets.UTF_8);
                    add(builder, parser, text);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not index the module model under " + root, e);
            }
        }
        contents.forEach((file, text) -> {
            if (!seen.contains(file) && sourceRoots.stream().anyMatch(r -> file.startsWith(r.toAbsolutePath().normalize()))) {
                add(builder, parser, text);
            }
        });
        return builder.build();
    }

    private static void add(ModuleModel.Builder builder, JavaParser parser, String text) {
        builder.hierarchy(text);
        if (text.contains("Entity") || text.contains("Embeddable") || text.contains("MappedSuperclass") || text.contains("validation")) {
            parser.parse(text).getResult().ifPresent(unit -> builder.add(unit, text));
        }
    }
}
