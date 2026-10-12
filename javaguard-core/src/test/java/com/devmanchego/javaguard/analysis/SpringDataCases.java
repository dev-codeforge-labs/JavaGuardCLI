package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.Framework;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The shared table of JG-SPR-015/016 cases ({@code spring-data-cases.txt}): each case becomes one repository interface in a
 * copy of the {@code spring-data-oracle} fixture, so JavaGuard and the real Spring Data judge the very same source.
 */
final class SpringDataCases {

    static final Path FIXTURE = Path.of("src/test/resources/fixtures/spring-data-oracle").toAbsolutePath().normalize();
    private static final Path TABLE = Path.of("src/test/resources/spring-data-cases.txt");
    private static final Map<String, String> ID_TYPES = Map.of("Customer", "Long", "PurchaseOrder", "Long", "Product", "String");

    /** One row; {@code entity} is empty for a JPQL case. */
    record Case(String name, String kind, String entity, String text, String oracle, String javaGuard) {
        String describe() {
            return name + " [" + (entity.isEmpty() ? "" : entity + ": ") + text + "]";
        }
    }

    private SpringDataCases() {
    }

    static List<Case> load() throws IOException {
        List<Case> cases = new ArrayList<>();
        int d = 0;
        int q = 0;
        for (String line : Files.readAllLines(TABLE, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            if (f[0].equals("D")) {
                cases.add(new Case(String.format("D%02d", ++d), "D", f[1], f[2], f[3], f[4]));
            } else {
                cases.add(new Case(String.format("Q%02d", ++q), "Q", "", f[1], f[2], f[3]));
            }
        }
        return cases;
    }

    /** Copies the fixture to {@code target} and writes one interface per case plus {@code cases.index}. */
    static void materialize(Path target, List<Case> cases) throws IOException {
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path source : walk.toList()) {
                String relative = FIXTURE.relativize(source).toString().replace('\\', '/');
                if (relative.startsWith("target")) {
                    continue;
                }
                Path to = target.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(source, to);
                }
            }
        }
        Path dir = Files.createDirectories(target.resolve("src/main/java/oracle/cases"));
        List<String> index = new ArrayList<>();
        for (Case c : cases) {
            Files.writeString(dir.resolve(c.name() + ".java"), source(c), StandardCharsets.UTF_8);
            index.add(c.name());
        }
        Files.write(target.resolve("cases.index"), index, StandardCharsets.UTF_8);
    }

    static Path sourceOf(Path project, Case c) {
        return project.resolve("src/main/java/oracle/cases/" + c.name() + ".java");
    }

    private static String source(Case c) {
        String entity = c.entity().isEmpty() ? "Customer" : c.entity();
        StringBuilder s = new StringBuilder("package oracle.cases;\n\n")
                .append("import java.math.BigDecimal;\nimport java.time.*;\nimport java.util.*;\n")
                .append("import oracle.domain.*;\nimport org.springframework.data.domain.*;\n")
                .append("import org.springframework.data.jpa.repository.*;\n\n")
                .append("public interface ").append(c.name()).append(" extends JpaRepository<").append(entity).append(", ")
                .append(ID_TYPES.get(entity)).append("> {\n");
        if (c.kind().equals("D")) {
            s.append("    ").append(c.text()).append(";\n");
        } else {
            boolean modifying = c.text().toLowerCase(java.util.Locale.ROOT).matches("^\\s*(update|delete)\\b.*");
            s.append(modifying ? "    @Modifying\n" : "").append("    @Query(\"").append(c.text().replace("\"", "\\\"")).append("\")\n")
                    .append(modifying ? "    int q();\n" : "    List<Object> q();\n");
        }
        return s.append("}\n").toString();
    }

    /** JavaGuard's verdict on one materialized case, with the module model of the fixture's domain. */
    static String javaGuard(Path project, Case c, EntityIndex index) {
        AstCheckEngine engine = new AstCheckEngine(EnumSet.of(Framework.SPRING_BOOT, Framework.SPRING_DATA_JPA, Framework.HIBERNATE,
                Framework.JAKARTA_EE), index, List.of());
        try {
            List<Finding> findings = engine.analyze("src/main/java/oracle/cases/" + c.name() + ".java",
                    Files.readString(sourceOf(project, c), StandardCharsets.UTF_8));
            return findings.stream().anyMatch(f -> f.ruleId().equals("JG-SPR-015") || f.ruleId().equals("JG-SPR-016"))
                    ? "FINDING" : "NONE";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Finding> findings(Path project, Case c, EntityIndex index) throws IOException {
        AstCheckEngine engine = new AstCheckEngine(EnumSet.of(Framework.SPRING_BOOT, Framework.SPRING_DATA_JPA), index, List.of());
        return engine.analyze("x.java", Files.readString(sourceOf(project, c), StandardCharsets.UTF_8));
    }
}
