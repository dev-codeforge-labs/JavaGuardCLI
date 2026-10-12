package com.devmanchego.javaguard.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Test fixture: prints the value of the environment variable named in {@code args[0]}. */
public final class EnvEchoTestHelper {

    public static void main(String[] args) throws IOException {
        String value = System.getenv(args[0]);
        System.out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
