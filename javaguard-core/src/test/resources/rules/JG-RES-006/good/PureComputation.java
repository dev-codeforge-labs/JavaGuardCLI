import java.util.concurrent.CompletableFuture;

class PureComputation {
    CompletableFuture<Integer> square(int n) {
        return CompletableFuture.supplyAsync(() -> n * n);
    }
}
