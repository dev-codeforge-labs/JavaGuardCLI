import java.util.Optional;

class OptionalReturnsNull {
    Optional<String> find(boolean found) {
        if (!found) {
            return null;
        }
        return Optional.of("x");
    }
}
