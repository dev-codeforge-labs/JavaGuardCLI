package com.devmanchego.javaguard.consult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stand-in for a second-model CLI, run through the current JVM so the tests need no external tool. First argument is the
 * mode: {@code ok} answers with the first line of the handoff read from stdin, the instruction it was given and the
 * recursion-guard variable; {@code fail} exits 3; {@code silent} exits 0 with no output; {@code sleep} outlives any
 * timeout; {@code marker} (used as the detached worker) writes {@code marker.txt} in the directory named by
 * {@code args[2]} after a short delay.
 */
public final class FakeConsult {

    private FakeConsult() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "ok" -> {
                String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                System.out.println("### Diagnosis");
                System.out.println("handoff-first-line: " + stdin.lines().findFirst().orElse("<empty>"));
                System.out.println("instruction: " + (args.length > 1 ? args[1] : ""));
                System.out.println("JAVAGUARD_DISABLED=" + System.getenv("JAVAGUARD_DISABLED"));
            }
            case "fail" -> {
                System.err.println("model unavailable");
                System.exit(3);
            }
            case "silent" -> System.exit(0);
            case "sleep" -> Thread.sleep(60_000);
            case "marker" -> marker(args);
            default -> System.exit(9);
        }
    }

    private static void marker(String[] args) throws IOException, InterruptedException {
        Thread.sleep(500);
        Files.writeString(Path.of(args[2]).resolve("marker.txt"), String.join(" ", args), StandardCharsets.UTF_8);
    }

    /** The command line (as a {@code consult.commands} override) that runs this class in the given mode. */
    public static String command(String mode) {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        String java = (Files.exists(executable) ? executable : Path.of(executable + ".exe")).toString();
        String classes;
        try {
            classes = Path.of(FakeConsult.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return "\"" + java + "\" -cp \"" + classes + "\" " + FakeConsult.class.getName() + " " + mode + " <instruction>";
    }
}
