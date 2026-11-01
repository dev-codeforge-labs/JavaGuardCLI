package com.devmanchego.javaguard.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SelfLauncherTest {

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        System.clearProperty("javaguard.launcher");
    }

    @Test
    void aLauncherThatDeclaresItselfIsWrittenAsIsNotItsJvm() throws IOException {
        Path launcher = Files.createFile(dir.resolve("my launcher.cmd"));
        System.setProperty("javaguard.launcher", launcher.toString());

        assertThat(SelfLauncher.resolve(null)).hasValue("\"" + launcher.toString().replace(File.separatorChar, '/') + "\"");
    }

    @Test
    void anExplicitCommandWinsOverTheDeclaredLauncher() throws IOException {
        System.setProperty("javaguard.launcher", Files.createFile(dir.resolve("declared")).toString());

        assertThat(SelfLauncher.resolve("/opt/jg/javaguard")).hasValue("\"/opt/jg/javaguard\"");
    }

    @Test
    void aDeclaredLauncherThatDoesNotExistIsIgnored() {
        System.setProperty("javaguard.launcher", dir.resolve("gone.cmd").toString());

        assertThat(SelfLauncher.resolve(null).orElse("")).doesNotContain("gone.cmd");
    }
}
