package com.devmanchego.javaguard.model;

/**
 * Finding category (specification &sect;6.1). Declaration order is the
 * category priority used for ranking in reports (specification &sect;12.2);
 * SUPPRESSION and INFRASTRUCTURE come last and never outrank real findings.
 */
public enum Category {
    SYNTAX,
    COMPILATION,
    TEST_FAILURE,
    TEST_INTEGRITY,
    SECRET,
    SECURITY,
    RELIABILITY,
    CONCURRENCY,
    SPRING_JPA,
    DATABASE,
    CONFIGURATION,
    PERFORMANCE,
    DEPENDENCY,
    API_COMPATIBILITY,
    AI_DEBRIS,
    ARCHITECTURE,
    SUPPRESSION,
    INFRASTRUCTURE
}
