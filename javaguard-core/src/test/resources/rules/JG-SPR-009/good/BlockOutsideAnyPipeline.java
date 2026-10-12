import reactor.core.publisher.Mono;

class BlockOutsideAnyPipeline {
    String main(Mono<String> input) {
        return input.block();
    }
}
