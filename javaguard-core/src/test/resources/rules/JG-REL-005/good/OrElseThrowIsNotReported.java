import java.util.Optional;

class OrElseThrowIsNotReported {
    String unwrap(Optional<String> value) {
        return value.orElseThrow();
    }
}
