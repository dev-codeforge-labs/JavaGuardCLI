package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.project.Framework;

import java.util.EnumSet;
import java.util.Set;

/** Groups of detected frameworks a rule can require (specification &sect;7.6, &sect;11.2). */
public final class Frameworks {

    /** Spring Boot or Spring Framework on the module's dependencies. */
    public static final Set<Framework> SPRING = EnumSet.of(Framework.SPRING_BOOT, Framework.SPRING_FRAMEWORK);

    /** Any JPA provider or API: Spring Data JPA, Hibernate, Jakarta or javax persistence. */
    public static final Set<Framework> JPA = EnumSet.of(Framework.SPRING_DATA_JPA, Framework.HIBERNATE,
            Framework.JAKARTA_PERSISTENCE, Framework.JAVAX_PERSISTENCE);

    private Frameworks() {
    }
}
