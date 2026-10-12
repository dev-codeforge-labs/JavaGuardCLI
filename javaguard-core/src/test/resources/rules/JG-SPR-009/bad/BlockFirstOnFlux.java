import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class BlockFirstOnFlux {
    Mono<Integer> run(Flux<Integer> input, Flux<Integer> other) {
        return Mono.fromSupplier(() -> other.blockFirst());
    }
}
