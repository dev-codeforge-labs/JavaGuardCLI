import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

class WithDedicatedExecutor {
    private final UserRepository userRepository;
    private final Executor ioExecutor;

    WithDedicatedExecutor(UserRepository userRepository, Executor ioExecutor) {
        this.userRepository = userRepository;
        this.ioExecutor = ioExecutor;
    }

    CompletableFuture<List<String>> names() {
        return CompletableFuture.supplyAsync(() -> userRepository.findAllNames(), ioExecutor);
    }
}
