import reactor.core.publisher.Flux;

class ThreadSleepInMap {
    Flux<Integer> run(Flux<Integer> input) {
        return input.map(v -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return v;
        });
    }
}
