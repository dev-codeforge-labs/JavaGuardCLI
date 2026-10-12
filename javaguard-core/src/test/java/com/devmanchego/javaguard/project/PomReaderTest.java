package com.devmanchego.javaguard.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PomReaderTest {

    @TempDir
    Path dir;

    private Path write(String xml) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, xml, StandardCharsets.UTF_8);
        return pom;
    }

    @Test
    void missingFileYieldsEmpty() {
        assertThat(PomReader.read(dir.resolve("nope.xml"))).isEmpty();
    }

    @Test
    void malformedXmlYieldsEmptyNotAnException() throws IOException {
        assertThat(PomReader.read(write("<project><unterminated"))).isEmpty();
    }

    @Test
    void nonPomXmlYieldsEmpty() throws IOException {
        assertThat(PomReader.read(write("<settings><localRepository>x</localRepository></settings>"))).isEmpty();
    }

    @Test
    void parentWithExplicitRelativePath() throws IOException {
        MavenPom pom = PomReader.read(write("""
                <project><parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                <relativePath>../../root/pom.xml</relativePath></parent></project>
                """)).orElseThrow();

        assertThat(pom.parentRelativePath()).contains("../../root/pom.xml");
    }

    @Test
    void parentWithoutRelativePathDefaultsToDotDotSlashPomXml() throws IOException {
        MavenPom pom = PomReader.read(write("<project><parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version></parent></project>"))
                .orElseThrow();

        assertThat(pom.parentRelativePath()).contains("../pom.xml");
    }

    @Test
    void noParentMeansEmptyRelativePath() throws IOException {
        assertThat(PomReader.read(write("<project></project>")).orElseThrow().parentRelativePath()).isEmpty();
    }

    @Test
    void modulesInDocumentOrder() throws IOException {
        MavenPom pom = PomReader.read(write(
                "<project><modules><module>b</module><module>a</module></modules></project>")).orElseThrow();

        assertThat(pom.modules()).containsExactly("b", "a");
    }

    @Test
    void sourceDirectoryOverrides() throws IOException {
        MavenPom pom = PomReader.read(write("""
                <project><build><sourceDirectory>legacy/main</sourceDirectory>
                <testSourceDirectory>legacy/test</testSourceDirectory></build></project>
                """)).orElseThrow();

        assertThat(pom.sourceDirectory()).contains("legacy/main");
        assertThat(pom.testSourceDirectory()).contains("legacy/test");
    }

    @Test
    void propertiesAreDirectChildrenOnly() throws IOException {
        MavenPom pom = PomReader.read(write("""
                <project><properties><maven.compiler.release>17</maven.compiler.release>
                <nested><ignored>x</ignored></nested></properties></project>
                """)).orElseThrow();

        assertThat(pom.properties()).containsEntry("maven.compiler.release", "17");
        assertThat(pom.properties()).doesNotContainKey("ignored");
    }

    @Test
    void compilerPluginConfigurationExtracted() throws IOException {
        MavenPom pom = PomReader.read(write("""
                <project><build><plugins>
                  <plugin><artifactId>some-other-plugin</artifactId>
                    <configuration><release>99</release></configuration></plugin>
                  <plugin><artifactId>maven-compiler-plugin</artifactId>
                    <configuration><release>11</release><source>11</source><target>11</target></configuration></plugin>
                </plugins></build></project>
                """)).orElseThrow();

        assertThat(pom.compilerPluginRelease()).contains("11");
        assertThat(pom.compilerPluginSource()).contains("11");
        assertThat(pom.compilerPluginTarget()).contains("11");
    }

    @Test
    void dependenciesIgnoreDependencyManagement() throws IOException {
        MavenPom pom = PomReader.read(write("""
                <project>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>only</groupId><artifactId>managed</artifactId></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """)).orElseThrow();

        assertThat(pom.dependencies()).containsExactly(new Coordinate("org.junit.jupiter", "junit-jupiter"));
    }

    @Test
    void xxeDoctypeIsRejectedNotExpanded(@TempDir Path externalDir) throws IOException {
        Path secret = externalDir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET", StandardCharsets.UTF_8);
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY xxe SYSTEM \"file:///"
                + secret.toUri().getPath().replace("\\", "/") + "\">]><project><modules><module>&xxe;</module></modules></project>";

        // Must fail closed (empty), never leak the external entity's content.
        assertThat(PomReader.read(write(xxe))).isEmpty();
    }

    @Test
    void realFixturePomsParse() {
        Path fixtures = Path.of("src/test/resources/fixtures");
        assertThat(PomReader.read(fixtures.resolve("maven-simple/pom.xml"))).isPresent();
        assertThat(PomReader.read(fixtures.resolve("maven-multimodule/pom.xml")).orElseThrow().modules())
                .containsExactly("module-a", "module-b");
        assertThat(PomReader.read(fixtures.resolve("legacy-dirty/pom.xml")).orElseThrow().sourceDirectory())
                .contains("legacy-src/main/java");
    }
}
