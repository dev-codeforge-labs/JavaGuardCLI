package com.devmanchego.javaguard.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test fixture: prints whether {@code args[0]}, resolved against the current directory, exists. */
public final class FileExistsTestHelper {

    public static void main(String[] args) throws IOException {
        // A relative Path resolves against the JVM's actual working directory,
        // which ProcessBuilder.directory(...) sets at OS level.
        boolean exists = Files.exists(Path.of(args[0]));
        System.out.write(String.valueOf(exists).getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
