package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.config.JavaGuardConfig.ArchRule;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.Framework;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 11: JG-ARCH-001 and the package patterns it shares with the API rule (specification &sect;11.6). */
class ArchitectureCheckTest {

    private static final ArchRule DOMAIN_NOT_WEB = new ArchRule("com.acme.domain..", List.of("com.acme.web.."));

    private static List<Finding> run(List<ArchRule> rules, String path, String source) {
        AstCheckEngine engine = new AstCheckEngine(EnumSet.noneOf(Framework.class), EntityIndex.empty(),
                rules.isEmpty() ? List.of() : List.of(new ArchitectureCheck(rules)));
        return engine.analyze(path, source).stream().filter(f -> f.ruleId().equals("JG-ARCH-001")).toList();
    }

    @Test
    void anImportOfAForbiddenPackageIsReportedOnTheImport() {
        String source = """
                package com.acme.domain.order;

                import com.acme.web.OrderController;
                import java.util.List;

                class Order {
                }
                """;

        List<Finding> findings = run(List.of(DOMAIN_NOT_WEB), "src/main/java/com/acme/domain/order/Order.java", source);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).line()).isEqualTo(3);
        assertThat(findings.get(0).message()).contains("com.acme.domain.order must not depend on com.acme.web..")
                .contains("com.acme.web.OrderController");
    }

    @Test
    void subPackagesOfBothSidesAreCovered() {
        String source = "package com.acme.domain.a.b;\nimport com.acme.web.admin.Panel;\nclass X {}\n";

        assertThat(run(List.of(DOMAIN_NOT_WEB), "X.java", source)).hasSize(1);
    }

    @Test
    void aFileOutsideTheFromPackageIsNotChecked() {
        String source = "package com.acme.service;\nimport com.acme.web.OrderController;\nclass S {}\n";

        assertThat(run(List.of(DOMAIN_NOT_WEB), "S.java", source)).isEmpty();
    }

    @Test
    void allowedImportsAndAnOverlappingNameAreNotReported() {
        String source = """
                package com.acme.domain;

                import com.acme.webhook.Hook;
                import com.acme.domain.shared.Money;
                import static com.acme.domain.shared.Money.zero;

                class D {
                }
                """;

        assertThat(run(List.of(DOMAIN_NOT_WEB), "D.java", source)).as("com.acme.webhook is not com.acme.web..").isEmpty();
    }

    @Test
    void wildcardAndStaticImportsAreResolvedToTheirPackage() {
        String source = "package com.acme.domain;\nimport com.acme.web.*;\nimport static com.acme.web.Util.helper;\nclass D {}\n";

        assertThat(run(List.of(DOMAIN_NOT_WEB), "D.java", source)).hasSize(2);
    }

    @Test
    void severalRulesAndSeveralForbiddenPackagesCombine() {
        List<ArchRule> rules = List.of(DOMAIN_NOT_WEB, new ArchRule("com.acme.api..", List.of("com.acme.persistence..", "java.sql..")));
        String source = "package com.acme.api;\nimport java.sql.Connection;\nimport com.acme.persistence.Dao;\nclass A {}\n";

        assertThat(run(rules, "A.java", source)).hasSize(2);
    }

    @Test
    void noRulesMeansNoFindings() {
        String source = "package com.acme.domain;\nimport com.acme.web.X;\nclass D {}\n";

        assertThat(run(List.of(), "D.java", source)).isEmpty();
    }

    @Test
    void theCacheKeyChangesWhenTheConfiguredRulesDo() {
        AstCheckEngine one = new AstCheckEngine(EnumSet.noneOf(Framework.class), EntityIndex.empty(),
                List.of(new ArchitectureCheck(List.of(DOMAIN_NOT_WEB))));
        AstCheckEngine other = new AstCheckEngine(EnumSet.noneOf(Framework.class), EntityIndex.empty(),
                List.of(new ArchitectureCheck(List.of(new ArchRule("com.acme.domain..", List.of("com.acme.db..."))))));
        AstCheckEngine none = new AstCheckEngine(EnumSet.noneOf(Framework.class), EntityIndex.empty(), List.of());

        assertThat(List.of(one.key(), other.key(), none.key())).doesNotHaveDuplicates();
    }

    @Test
    void packageGlobsFollowTheDocumentedSyntax() {
        assertThat(PackageGlobs.matches("com.acme..", "com.acme")).isTrue();
        assertThat(PackageGlobs.matches("com.acme..", "com.acme.x.y")).isTrue();
        assertThat(PackageGlobs.matches("com.acme..", "com.acmex")).isFalse();
        assertThat(PackageGlobs.matches("com.acme.**", "com.acme.x")).isTrue();
        assertThat(PackageGlobs.matches("com.acme.*", "com.acme.x")).isTrue();
        assertThat(PackageGlobs.matches("com.acme.*", "com.acme.x.y")).isFalse();
        assertThat(PackageGlobs.matches("com.*.api", "com.acme.api")).isTrue();
        assertThat(PackageGlobs.matches("com.acme.api", "com.acme.api")).isTrue();
        assertThat(PackageGlobs.matches("com.acme.api", "com.acme.api.v2")).isFalse();
    }

    @Test
    void importsResolveToTheirPackageByTheFirstCapitalizedSegment() {
        assertThat(PackageGlobs.packageOfImport("a.b.C")).isEqualTo("a.b");
        assertThat(PackageGlobs.packageOfImport("a.b.C.Inner")).isEqualTo("a.b");
        assertThat(PackageGlobs.packageOfImport("a.b.C.member")).isEqualTo("a.b");
        assertThat(PackageGlobs.packageOfImport("a.b")).isEqualTo("a.b");
    }
}
