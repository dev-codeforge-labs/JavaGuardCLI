package com.devmanchego.javaguard.project;

/**
 * Frameworks whose presence changes which rules are active (specification
 * &sect;7.6, catalog &sect;1.6). Detected from dependency coordinates only
 * (declared, not inherited-but-unused); see {@link ProjectDetector}.
 */
public enum Framework {
    SPRING_BOOT,
    SPRING_FRAMEWORK,
    SPRING_DATA_JPA,
    HIBERNATE,
    /** {@code jakarta.persistence.*} namespace in use. */
    JAKARTA_PERSISTENCE,
    /** {@code javax.persistence.*} namespace in use (pre-Jakarta EE 9). */
    JAVAX_PERSISTENCE,
    /** The build is on the {@code jakarta.*} namespace (Spring Boot 3+, Jakarta EE 9+); see {@link JakartaDetector}. */
    JAKARTA_EE,
    LOMBOK,
    MAPSTRUCT,
    JUNIT4,
    JUNIT5,
    MOCKITO,
    TESTCONTAINERS
}
