package com.devmanchego.javaguard.testing;

import com.devmanchego.javaguard.config.JavaGuardConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Which tests the end-of-turn gate runs (specification &sect;10.3 step 3).
 *
 * @param scope            the scope actually applied; {@code MODULE} when {@code AFFECTED} fell back
 * @param classes          fully qualified test classes for {@code -Dtest}; empty when {@code wholeModules}
 * @param moduleRoots      modules to run tests in
 * @param wholeModules     run every test of {@code moduleRoots} instead of the listed classes
 * @param relatedClasses   test classes related to the change (named after it, referencing it, or changed
 *                         themselves); a failure outside this set is "possibly pre-existing"
 * @param fellBackToModule {@code AFFECTED} exceeded {@code tests.maxClasses}
 * @param notes            explanations for the user
 */
public record TestSelection(
        JavaGuardConfig.TestScope scope,
        List<String> classes,
        Set<Path> moduleRoots,
        boolean wholeModules,
        Set<String> relatedClasses,
        boolean fellBackToModule,
        List<String> notes
) {

    public TestSelection {
        classes = List.copyOf(classes);
        moduleRoots = Set.copyOf(moduleRoots);
        relatedClasses = Set.copyOf(relatedClasses);
        notes = List.copyOf(notes);
    }

    /** True when there is nothing to run. */
    public boolean isEmpty() {
        return !wholeModules && classes.isEmpty();
    }

    public static TestSelection none(JavaGuardConfig.TestScope scope, List<String> notes) {
        return new TestSelection(scope, List.of(), Set.of(), false, Set.of(), false, notes);
    }
}
