package com.devmanchego.javaguard.adapter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadReaderTest {

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsTheWholeStream() throws PayloadException {
        byte[] bytes = PayloadReader.read(stream("{\"a\":1}"), Duration.ofSeconds(5), 1024);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    void rejectsPayloadsAboveTheSizeLimit() {
        assertThatThrownBy(() -> PayloadReader.read(stream("x".repeat(100)), Duration.ofSeconds(5), 10))
                .isInstanceOf(PayloadException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void timesOutOnAStreamThatNeverEnds() {
        InputStream blocking = new InputStream() {
            @Override
            public int read() {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
        long start = System.nanoTime();

        assertThatThrownBy(() -> PayloadReader.read(blocking, Duration.ofMillis(200), 1024))
                .isInstanceOf(PayloadException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void parsesAJsonObject() throws PayloadException {
        assertThat(PayloadReader.parseJson("{\"hook_event_name\":\"Stop\"}".getBytes(StandardCharsets.UTF_8))
                .path("hook_event_name").asText()).isEqualTo("Stop");
    }

    @Test
    void parsesUtf8WithByteOrderMark() throws PayloadException {
        byte[] withBom = ("﻿{\"k\":\"ñ\"}").getBytes(StandardCharsets.UTF_8);

        assertThat(PayloadReader.parseJson(withBom).path("k").asText()).isEqualTo("ñ");
    }

    @Test
    void rejectsEmptyPayload() {
        assertThatThrownBy(() -> PayloadReader.parseJson(new byte[0]))
                .isInstanceOf(PayloadException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> PayloadReader.parseJson("  \n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PayloadException.class).hasMessageContaining("empty");
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> PayloadReader.parseJson("{not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PayloadException.class).hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsJsonThatIsNotAnObject() {
        assertThatThrownBy(() -> PayloadReader.parseJson("[1,2]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PayloadException.class).hasMessageContaining("object");
    }
}
