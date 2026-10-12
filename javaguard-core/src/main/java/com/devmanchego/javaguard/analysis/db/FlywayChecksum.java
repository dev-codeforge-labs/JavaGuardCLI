package com.devmanchego.javaguard.analysis.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Flyway's checksum of a SQL migration ({@code ChecksumCalculator}): a CRC32 over the UTF-8 bytes of every line, line
 * terminators excluded and a leading byte-order mark removed. So a change of line endings, or adding or removing empty
 * lines, keeps the checksum (Flyway accepts it); any other edit, trailing spaces included, changes it and Flyway's
 * validation fails on every database that applied the migration.
 */
public final class FlywayChecksum {

    private static final char BOM = '﻿';

    private FlywayChecksum() {
    }

    public static int of(String content) {
        CRC32 crc = new CRC32();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line = reader.readLine();
            if (line != null && !line.isEmpty() && line.charAt(0) == BOM) {
                line = line.substring(1);
            }
            while (line != null) {
                crc.update(line.getBytes(StandardCharsets.UTF_8));
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return (int) crc.getValue();
    }
}
