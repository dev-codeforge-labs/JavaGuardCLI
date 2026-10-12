package com.devmanchego.javaguard.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Test fixture: writes a fixed non-ASCII string as raw UTF-8 bytes (bypassing
 * {@code PrintStream}'s own charset, exactly like the console code page a
 * real build tool's output would otherwise be mangled by on Windows) and
 * exits with the given code.
 */
public final class OutputEchoTestHelper {

    static final String TEXT = "café ñoño 中文 line2\n";

    public static void main(String[] args) throws IOException {
        System.out.write(TEXT.getBytes(StandardCharsets.UTF_8));
        System.out.flush();
        System.exit(Integer.parseInt(args[0]));
    }
}
