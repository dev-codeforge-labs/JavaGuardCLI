package com.devmanchego.javaguard.cli;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every command must at least parse and answer {@code --help} with exit 0
 * (plan Phase 0 test: "CLI smoke: --help for every command exits 0").
 */
class MainSmokeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "hook", "check", "install", "uninstall", "doctor", "warmup",
            "capture", "handoff", "rules", "state", "state clear",
            "baseline", "baseline reset"
    })
    void everyCommandAnswersHelpWithExitZero(String command) {
        String[] args = (command + " --help").split(" ");
        int exitCode = new CommandLine(new Main()).execute(args);

        assertThat(exitCode).isZero();
    }

    @org.junit.jupiter.api.Test
    void topLevelHelpExitsZero() {
        assertThat(new CommandLine(new Main()).execute("--help")).isZero();
    }

    @org.junit.jupiter.api.Test
    void topLevelVersionExitsZero() {
        assertThat(new CommandLine(new Main()).execute("--version")).isZero();
    }

    @org.junit.jupiter.api.Test
    void noSubcommandExitsZeroAndPrintsUsage() {
        assertThat(new CommandLine(new Main()).execute()).isZero();
    }

    @org.junit.jupiter.api.Test
    void unknownSubcommandIsAUsageError() {
        int exitCode = new CommandLine(new Main()).execute("not-a-command");

        assertThat(exitCode).isEqualTo(ExitCode.USAGE_ERROR.code());
    }
}
