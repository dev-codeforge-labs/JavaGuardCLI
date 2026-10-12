import java.util.List;
import java.util.concurrent.CompletableFuture;

class RepositoryOnCommonPool {
    private final UserRepository userRepository;

    RepositoryOnCommonPool(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    CompletableFuture<List<String>> names() {
        return CompletableFuture.supplyAsync(() -> userRepository.findAllNames());
    }
}
