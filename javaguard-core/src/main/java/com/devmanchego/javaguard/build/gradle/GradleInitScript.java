package com.devmanchego.javaguard.build.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JavaGuard's Gradle init script ({@code javaguard-init.gradle}, a classpath resource): copied to a temporary file for the
 * duration of one Gradle invocation and passed with {@code --init-script}, so nothing is ever written into the project.
 */
final class GradleInitScript implements AutoCloseable {

    private static final String RESOURCE = "/com/devmanchego/javaguard/build/gradle/javaguard-init.gradle";

    private final Path file;

    private GradleInitScript(Path file) {
        this.file = file;
    }

    static GradleInitScript create() {
        try (InputStream in = GradleInitScript.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            Path temp = Files.createTempFile("javaguard-init", ".gradle");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return new GradleInitScript(temp);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path path() {
        return file;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // A leftover temp file is harmless.
        }
    }
}
