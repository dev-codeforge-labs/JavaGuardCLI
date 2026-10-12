package com.devmanchego.javaguard.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads the hook payload from stdin: fully, UTF-8, bounded in size and time
 * (contracts &sect;1.1). A stuck pipe must never keep the agent waiting past
 * JavaGuard's own budget.
 */
public final class PayloadReader {

    /** Contracts &sect;1.1: 5 s read timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** Generous upper bound; Write payloads carry whole files. */
    public static final int DEFAULT_MAX_BYTES = 16 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadReader() {
    }

    public static byte[] read(InputStream in, Duration timeout, int maxBytes) throws PayloadException {
        FutureTask<byte[]> task = new FutureTask<>(() -> readBounded(in, maxBytes));
        Thread reader = new Thread(task, "javaguard-stdin-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new PayloadException("Reading the hook payload timed out after " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PayloadException pe) {
                throw pe;
            }
            throw new PayloadException("Failed to read the hook payload: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PayloadException("Interrupted while reading the hook payload", e);
        }
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException, PayloadException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > maxBytes) {
                throw new PayloadException("Hook payload exceeds " + maxBytes + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Parses the payload as a JSON object (UTF-8, optional BOM). */
    public static JsonNode parseJson(byte[] raw) throws PayloadException {
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw new PayloadException("Hook payload is empty");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new PayloadException("Hook payload is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new PayloadException("Hook payload must be a JSON object");
        }
        return node;
    }
}
