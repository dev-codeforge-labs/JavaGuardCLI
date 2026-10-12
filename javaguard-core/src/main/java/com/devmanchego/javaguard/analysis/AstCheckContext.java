package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What an {@link AstCheck} sees: the parsed file, its source lines (for
 * snippets), whether it is a test source (a few rules relax or change
 * severity there, e.g. catalog JG-SEC-008), and every comment lexed from the
 * file. Comments are passed separately, unattached to any AST node, because
 * the engine parses with {@code setAttributeComments(false)} for speed; a
 * check that needs a comment near a node matches on line ranges instead of
 * node identity (catalog JG-AI-003, JG-AI-004).
 */
public record AstCheckContext(String relativePath, CompilationUnit unit, List<String> lines, boolean testScope,
                               List<Comment> comments, java.util.Set<com.devmanchego.javaguard.project.Framework> frameworks,
                               EntityIndex entities) {

    public AstCheckContext {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(unit, "unit");
        lines = List.copyOf(lines);
        comments = List.copyOf(comments);
        frameworks = java.util.Set.copyOf(frameworks);
        Objects.requireNonNull(entities, "entities");
    }

    /** The {@code @Entity} classes declared in this very file, by simple name. */
    java.util.Map<String, java.util.Set<String>> entitiesInFile() {
        return EntityIndex.entitiesIn(unit);
    }

    /** True when {@code simpleName} is an entity declared in this file or anywhere in the module. */
    boolean isEntity(String simpleName) {
        return entitiesInFile().containsKey(simpleName) || entities.isEntity(simpleName);
    }

    /** To-many getter names of an entity, from this file or the module index. */
    java.util.Set<String> toManyGetters(String entitySimpleName) {
        java.util.Set<String> local = entitiesInFile().get(entitySimpleName);
        return local != null ? local : entities.toManyGetters(entitySimpleName);
    }

    /** The module model as the rules see it: the module on disk, overlaid with what this very file declares. */
    ModuleModel model() {
        return entities.model().overlay(ModuleModel.of(unit));
    }

    Path path() {
        return Path.of(relativePath);
    }
}
