import org.springframework.scheduling.annotation.Async;

class AsyncReturnsString {
    @Async
    public String compute() {
        return "lost";
    }
}
