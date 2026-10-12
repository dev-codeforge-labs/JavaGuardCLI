package com.devmanchego.javaguard.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture for {@link ProcessRunnerTest#timeoutKillsTheWholeProcessTree}:
 * started in {@code "parent"} mode, it records its own PID, spawns a copy of
 * itself in {@code "child"} mode (recording that PID too), then both sleep
 * far longer than the test's timeout. If {@link ProcessRunner} did not kill
 * descendants, the child would survive the parent's death.
 */
public final class ProcessTreeTestHelper {

    private static final long SLEEP_MILLIS = 120_000;

    public static void main(String[] args) throws IOException, InterruptedException {
        String mode = args[0];
        Path pidDir = Path.of(args[1]);
        Files.writeString(pidDir.resolve(mode + ".pid"), String.valueOf(ProcessHandle.current().pid()),
                StandardCharsets.UTF_8);
        if ("parent".equals(mode)) {
            List<String> command = new ArrayList<>(TestJvm.command(ProcessTreeTestHelper.class, "child", args[1]));
            new ProcessBuilder(command).start();
        }
        Thread.sleep(SLEEP_MILLIS);
    }
}
