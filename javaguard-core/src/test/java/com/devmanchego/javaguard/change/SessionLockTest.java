package com.devmanchego.javaguard.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionLockTest {

    @Test
    void secondThreadTimesOutWhileTheFirstHoldsTheLock(@TempDir Path dir) throws Exception {
        Path lockFile = dir.resolve("s/.lock");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try (SessionLock ignored = SessionLock.acquire(lockFile, Duration.ofSeconds(2))) {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        held.await(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> SessionLock.acquire(lockFile, Duration.ofMillis(150)))
                .isInstanceOf(SessionLock.TimeoutException.class);

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void lockIsReusableAfterRelease(@TempDir Path dir) throws Exception {
        Path lockFile = dir.resolve(".lock");

        try (SessionLock ignored = SessionLock.acquire(lockFile, Duration.ofSeconds(1))) {
            assertThat(lockFile).exists();
        }
        try (SessionLock ignored = SessionLock.acquire(lockFile, Duration.ofSeconds(1))) {
            assertThat(lockFile).exists();
        }
    }
}
