package com.devmanchego.javaguard.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 22: JG-SPR-013 runs only on builds that are on the jakarta namespace; a Boot 2 build keeps it silent. */
class JakartaDetectorTest {

    private static String parent(String version) {
        return "<project><parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>"
                + "<version>" + version + "</version><relativePath/></parent></project>";
    }

    @Test
    void springBootParentDecidesByItsMajorVersion() {
        assertThat(JakartaDetector.jakartaNamespace(parent("3.3.4"))).isTrue();
        assertThat(JakartaDetector.jakartaNamespace(parent("2.7.18"))).isFalse();
    }

    @Test
    void aBomVersionGivenByAPropertyOfTheSameFileIsResolved() {
        String pom = """
                <project>
                  <properties><spring-boot.version>3.2.0</spring-boot.version></properties>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-dependencies</artifactId>
                    <version>${spring-boot.version}</version>
                    <type>pom</type><scope>import</scope>
                  </dependency></dependencies></dependencyManagement>
                </project>
                """;

        assertThat(JakartaDetector.jakartaNamespace(pom)).isTrue();
        assertThat(JakartaDetector.jakartaNamespace(pom.replace("3.2.0", "2.7.5"))).isFalse();
        assertThat(JakartaDetector.jakartaNamespace(pom.replace("<properties><spring-boot.version>3.2.0</spring-boot.version></properties>", "")))
                .as("an undefined property is unknown, so no").isFalse();
    }

    @Test
    void jakartaEe8ArtifactsStillShipJavaxPackagesAndDoNotCount() {
        String boot2 = "<dependency><groupId>jakarta.persistence</groupId><artifactId>jakarta.persistence-api</artifactId>"
                + "<version>2.2.3</version></dependency><dependency><groupId>jakarta.validation</groupId>"
                + "<artifactId>jakarta.validation-api</artifactId><version>2.0.2</version></dependency>";
        String ee9 = "<dependency><groupId>jakarta.servlet</groupId><artifactId>jakarta.servlet-api</artifactId>"
                + "<version>6.0.0</version></dependency>";

        assertThat(JakartaDetector.jakartaNamespace(boot2)).isFalse();
        assertThat(JakartaDetector.jakartaNamespace(ee9)).isTrue();
    }

    @Test
    void hibernate6AndSpring6CountAndHibernate5DoesNot() {
        assertThat(JakartaDetector.jakartaNamespace("<groupId>org.hibernate.orm</groupId>")).isTrue();
        assertThat(JakartaDetector.jakartaNamespace("<dependency><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId>"
                + "<version>5.6.15.Final</version></dependency>")).isFalse();
        assertThat(JakartaDetector.jakartaNamespace("<dependency><groupId>org.springframework</groupId><artifactId>spring-webmvc</artifactId>"
                + "<version>6.1.3</version></dependency>")).isTrue();
    }

    @Test
    void gradlePluginAndCoordinatesAreRead() {
        assertThat(JakartaDetector.jakartaNamespace("plugins {\n    id 'org.springframework.boot' version '3.3.0'\n}\n")).isTrue();
        assertThat(JakartaDetector.jakartaNamespace("plugins {\n    id(\"org.springframework.boot\") version \"2.7.18\"\n}\n")).isFalse();
        assertThat(JakartaDetector.jakartaNamespace("dependencies {\n    implementation 'jakarta.persistence:jakarta.persistence-api:3.1.0'\n}\n"))
                .isTrue();
        assertThat(JakartaDetector.jakartaNamespace("dependencies {\n    implementation \"org.springframework.boot:spring-boot-starter-web:${bootVersion}\"\n}\n"))
                .as("a Gradle variable is unknown, so no").isFalse();
    }

    @Test
    void aMavenModuleInheritsTheDecisionFromItsReactorPom(@TempDir Path reactor) throws IOException {
        Files.writeString(reactor.resolve("pom.xml"), parent("3.1.5").replace("</project>", "<modules><module>app</module></modules></project>"));
        Path app = Files.createDirectories(reactor.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");

        assertThat(ProjectDetector.detectFrameworks(app, reactor, BuildTool.MAVEN)).contains(Framework.JAKARTA_EE);
        assertThat(ProjectDetector.detectFrameworks(app, app, BuildTool.MAVEN)).doesNotContain(Framework.JAKARTA_EE);
    }

    @Test
    void aGradleSubprojectReadsTheRootBuildFile(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("settings.gradle"), "include 'app'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins {\n    id 'org.springframework.boot' version '3.3.0' apply false\n}\n");
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("build.gradle"), "plugins { id 'java' }\n");

        assertThat(ProjectDetector.detectFrameworks(app, root, BuildTool.GRADLE)).contains(Framework.JAKARTA_EE);
    }
}
