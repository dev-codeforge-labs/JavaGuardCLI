import java.util.Optional;

class OptionalReturnsEmpty {
    Optional<String> find(boolean found) {
        if (!found) {
            return Optional.empty();
        }
        return Optional.of("x");
    }
}
