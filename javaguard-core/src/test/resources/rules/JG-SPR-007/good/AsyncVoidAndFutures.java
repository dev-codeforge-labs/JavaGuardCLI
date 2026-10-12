import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.concurrent.ListenableFuture;

class AsyncVoidAndFutures {
    @Async
    public void fireAndForget() {
    }

    @Async
    public CompletableFuture<String> completable() {
        return CompletableFuture.completedFuture("x");
    }

    @Async
    public Future<String> future() {
        return CompletableFuture.completedFuture("x");
    }

    @Async
    public ListenableFuture<String> listenable() {
        return null;
    }
}
