import java.util.List;
import org.springframework.scheduling.annotation.Async;

class AsyncReturnsList {
    @Async
    public List<String> compute() {
        return List.of();
    }
}
