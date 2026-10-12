package com.devmanchego.javaguard.dist.spike;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Phase 0 packaging spike (plan 04, "Packaging spike"; specification &sect;21,
 * decision D12).
 *
 * <p>This class is deliberately trivial and has nothing to do with
 * JavaGuardCLI's real logic. Its only purpose is to answer, before any real
 * code is written against the hook channel, one question: <em>does a
 * Launch4j {@code console} executable, wrapping a jlink runtime, correctly
 * pass stdin through to the JVM, byte-for-byte, and propagate the JVM's exit
 * code back to the OS?</em> If not, nothing else in this project can work,
 * since every hook contract (.private/spec/03-HOOK-CONTRACTS.md) depends on
 * exactly that.
 *
 * <p>Usage: {@code echospike <exit-code>}. Reads all of stdin, writes it
 * unchanged to stdout, then exits with the given code.
 *
 * <p>This class (and the {@code javaguard-dist} spike wiring around it) is
 * throwaway: it is not part of the {@code javaguard} product surface and is
 * superseded once Phase 19 builds the real distribution.
 */
public final class EchoSpike {

    private EchoSpike() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: echospike <exit-code>");
            System.exit(2);
        }
        int exitCode = Integer.parseInt(args[0]);

        try (InputStream in = System.in; OutputStream out = System.out) {
            in.transferTo(out);
            out.flush();
        }

        System.exit(exitCode);
    }
}
