import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PureReactiveOperators {
    Mono<String> run(Mono<String> input) {
        return input.map(String::toUpperCase).flatMap(v -> Mono.just(v + "!"));
    }

    Flux<Integer> numbers(Flux<Integer> input) {
        return input.filter(v -> v > 0).map(v -> v * 2);
    }
}
