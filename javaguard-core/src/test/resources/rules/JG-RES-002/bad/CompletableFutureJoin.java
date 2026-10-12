import java.util.concurrent.CompletableFuture;

class CompletableFutureJoin {
    String await(CompletableFuture<String> result) {
        return result.join();
    }
}
