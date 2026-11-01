package com.devmanchego.javaguard.cli.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StartupArchiveTest {

    @Test
    void theTrainingJvmKeepsTheRunningFlagsExceptTheOnesThatChooseAnArchive() {
        List<String> flags = StartupArchive.jvmFlags(List.of("-XX:SharedArchiveFile=/old/app.jsa", "-Xlog:disable",
                "-XX:TieredStopAtLevel=1", "-XX:+AutoCreateSharedArchive", "-Xshare:auto", "-XX:ArchiveClassesAtExit=x.jsa",
                "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED", "-Xmx1g"));

        assertThat(flags).containsExactly("-Xlog:disable", "-XX:TieredStopAtLevel=1",
                "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED", "-Xmx1g");
    }

    @Test
    void thereIsNothingToBuildOutsideThePackagedJar() {
        assertThat(StartupArchive.target()).isEmpty();
        assertThat(new OptimizeCommand().call()).isZero();
    }
}
