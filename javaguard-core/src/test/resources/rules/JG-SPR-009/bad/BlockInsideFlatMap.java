import reactor.core.publisher.Mono;

class BlockInsideFlatMap {
    Mono<String> run(Mono<String> input, Mono<String> other) {
        return input.flatMap(v -> Mono.just(other.block() + v));
    }
}
