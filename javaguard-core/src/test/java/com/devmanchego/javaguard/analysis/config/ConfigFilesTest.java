package com.devmanchego.javaguard.analysis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Plan Phase 23: configuration files read into flattened keys with lines. */
class ConfigFilesTest {

    @Test
    void nestedYamlBecomesDottedKeysWithTheirLines() {
        String yaml = """
                # comment
                spring:
                  datasource:
                    url: "jdbc:postgresql://db/app"   # quoted
                    password: 's3cret #notacomment'
                  jpa.open-in-view: false
                server:
                  port: 8080
                """;

        List<ConfigFiles.Entry> entries = ConfigFiles.parse("src/main/resources/application.yml", yaml);

        assertThat(entries).extracting(ConfigFiles.Entry::key, ConfigFiles.Entry::value, ConfigFiles.Entry::line).containsExactly(
                tuple("spring.datasource.url", "jdbc:postgresql://db/app", 4),
                tuple("spring.datasource.password", "s3cret #notacomment", 5),
                tuple("spring.jpa.open-in-view", "false", 6),
                tuple("server.port", "8080", 8));
    }

    @Test
    void listsGetIndexesAndBlockScalarsAreSkipped() {
        String yaml = """
                app:
                  servers:
                    - host: a
                      port: 1
                    - host: b
                  banner: |
                    password: not-a-key
                    more text
                  after: x
                top:
                - name: same-indent
                """;

        List<ConfigFiles.Entry> entries = ConfigFiles.parse("application.yaml", yaml);

        assertThat(entries).extracting(ConfigFiles.Entry::key).containsExactly("app.servers[0].host", "app.servers[0].port",
                "app.servers[1].host", "app.banner", "app.after", "top[0].name");
    }

    @Test
    void yamlDocumentsCarryTheirProfile() {
        String yaml = """
                app.key: one
                ---
                spring:
                  config:
                    activate:
                      on-profile: dev
                app.key: two
                """;

        List<ConfigFiles.Entry> entries = ConfigFiles.parse("application.yml", yaml);

        assertThat(entries).filteredOn(e -> e.key().equals("app.key")).extracting(ConfigFiles.Entry::profile).containsExactly("", "dev");
    }

    @Test
    void propertiesSupportEverySeparatorContinuationsAndComments() {
        String properties = """
                # comment
                ! also a comment
                a.b=1
                c.d: 2
                e.f 3
                g.h = multi \\
                    line
                key\\:with\\:colons=4
                """;

        List<ConfigFiles.Entry> entries = ConfigFiles.parse("application-prod.properties", properties);

        assertThat(entries).extracting(ConfigFiles.Entry::key, ConfigFiles.Entry::value, ConfigFiles.Entry::line).containsExactly(
                tuple("a.b", "1", 3), tuple("c.d", "2", 4), tuple("e.f", "3", 5), tuple("g.h", "multi line", 6),
                tuple("key:with:colons", "4", 8));
        assertThat(entries).allSatisfy(e -> assertThat(e.profile()).isEqualTo("prod"));
    }

    @ParameterizedTest
    @CsvSource({"application.properties, true", "application-dev.yml, true", "bootstrap.yaml, true", "src/main/resources/application-k8s.yaml, true",
            "app.properties, false", "application.json, false", "logback.xml, false", "my-application.yml, false"})
    void recognisesSpringBootConfigurationFileNames(String path, boolean expected) {
        assertThat(ConfigFiles.isConfigFile(path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"app.mail-host, app.mailHost", "app.mail-host, APP_MAIL_HOST", "app.mail_host, app.mail-host", "app.list[0].name, app.list.name"})
    void relaxedBindingVariantsShareACanonicalForm(String a, String b) {
        assertThat(ConfigFiles.canonical(a)).isEqualTo(ConfigFiles.canonical(b));
    }
}
