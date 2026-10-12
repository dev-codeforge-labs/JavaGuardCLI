package com.devmanchego.javaguard.analysis.config;

import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 23: JG-CFG-002 at the end of the turn, reporting only what the turn broke. */
class ConfigKeyAnalyzerTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>shop</groupId><artifactId>shop</artifactId><version>1</version>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                  <version>3.3.4</version>
                </dependency>
              </dependencies>
            </project>
            """;
    private static final String MAILER = "src/main/java/shop/Mailer.java";
    private static final String YML = "src/main/resources/application.yml";

    @TempDir
    Path root;

    @TempDir
    Path home;

    private final Map<String, String> baselines = new HashMap<>();

    @BeforeEach
    void project() throws IOException {
        Files.writeString(root.resolve("pom.xml"), POM);
        write(YML, "app:\n  mail-host: smtp.example.com\n  retries: 3\n");
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String mailer(String annotation) {
        return "package shop;\n\nimport org.springframework.beans.factory.annotation.Value;\n\nclass Mailer {\n    " + annotation
                + "\n    String value;\n}\n";
    }

    private List<Finding> analyze(String... changed) throws Exception {
        var config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), home, Map.of())).config();
        List<Path> files = java.util.Arrays.stream(changed).map(root::resolve).toList();
        DeepAnalyzer.Context context = new DeepAnalyzer.Context(files, ReactorModules.load(root), Duration.ofMinutes(1),
                file -> Optional.ofNullable(baselines.get(root.relativize(file).toString().replace('\\', '/'))), config,
                new JavaGuardPaths(root));
        return new ConfigKeyAnalyzer().analyze(context);
    }

    @Test
    void aValueWithoutDefaultThatNoFileDefinesIsReportedAtItsLine() throws Exception {
        write(MAILER, mailer("@Value(\"${app.mail-port}\")"));

        List<Finding> findings = analyze(MAILER);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-CFG-002");
            assertThat(f.line()).isEqualTo(6);
            assertThat(f.message()).contains("app.mail-port");
            assertThat(f.remediation()).contains("${app.mail-port:...}");
        });
    }

    @Test
    void relaxedBindingDefaultsPrefixesEnvironmentNamesAndSystemPropertiesAreFine() throws Exception {
        write(MAILER, mailer("@Value(\"${app.mailHost} ${APP_MAIL_HOST} ${app.missing:x} ${PORT} ${user.home} ${app} #{2 * 3}\")"));

        assertThat(analyze(MAILER)).isEmpty();
    }

    @Test
    void aKeyRemovedFromTheConfigurationIsReportedOnTheUnchangedCode() throws Exception {
        write(MAILER, mailer("@Value(\"${app.retries}\")"));
        baselines.put(YML, "app:\n  mail-host: smtp.example.com\n  retries: 3\n");
        write(YML, "app:\n  mail-host: smtp.example.com\n");

        List<Finding> findings = analyze(YML);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.file()).contains(Path.of(MAILER));
            assertThat(f.message()).contains("app.retries");
        });
    }

    @Test
    void aProblemThatWasAlreadyThereIsNotBlamedOnTheTurn() throws Exception {
        String old = mailer("@Value(\"${app.never-defined}\")");
        write(MAILER, old.replace("String value;", "String value;\n    int other;"));
        baselines.put(MAILER, old);

        assertThat(analyze(MAILER)).isEmpty();
    }

    @Test
    void requiredPropertiesOfValidatedConfigurationPropertiesAreChecked() throws Exception {
        String properties = """
                package shop;

                @org.springframework.validation.annotation.Validated
                @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app")
                record AppProperties(@jakarta.validation.constraints.NotBlank String mailHost,
                                     @jakarta.validation.constraints.NotNull Integer timeout,
                                     String optional) {
                }
                """;
        write("src/main/java/shop/AppProperties.java", properties);

        List<Finding> findings = analyze("src/main/java/shop/AppProperties.java");

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message()).contains("app.timeout"));
    }

    @Test
    void modulesThatImportConfigurationFromElsewhereAreNotJudged() throws Exception {
        write(YML, "spring:\n  config:\n    import: optional:configserver:http://config:8888\n");
        write(MAILER, mailer("@Value(\"${app.mail-port}\")"));

        assertThat(analyze(MAILER)).isEmpty();
    }

    @Test
    void aKeyDefinedByTheApplicationModuleCountsForALibraryModule() throws Exception {
        // Found on the Phase 26 corpus (fineract): libraries read keys that only the application module defines.
        Files.writeString(root.resolve("pom.xml"), POM.replace("<version>1</version>", "<version>1</version><packaging>pom</packaging>"
                + "<modules><module>app</module><module>lib</module></modules>"));
        for (String module : List.of("app", "lib")) {
            Files.createDirectories(root.resolve(module));
            Files.writeString(root.resolve(module + "/pom.xml"), POM.replace("<artifactId>shop</artifactId><version>1</version>",
                    "<parent><groupId>shop</groupId><artifactId>shop</artifactId><version>1</version></parent><artifactId>" + module
                            + "</artifactId>"));
        }
        write("app/src/main/resources/application.properties", "lib.mode=fast\n");
        String lib = "lib/src/main/java/shop/Lib.java";
        write(lib, mailer("@Value(\"${lib.mode}\") String a;\n    @Value(\"${lib.missing}\")").replace("class Mailer", "class Lib"));

        List<Finding> findings = analyze(lib);

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message()).contains("lib.missing"));
    }

    @Test
    void withoutSpringBootNothingIsJudged() throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM.replace("org.springframework.boot", "org.example"));
        write(MAILER, mailer("@Value(\"${app.mail-port}\")"));

        assertThat(analyze(MAILER)).isEmpty();
    }
}
