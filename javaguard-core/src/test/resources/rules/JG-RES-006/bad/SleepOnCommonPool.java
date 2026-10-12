import java.util.concurrent.CompletableFuture;

class SleepOnCommonPool {
    CompletableFuture<Void> later() {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
