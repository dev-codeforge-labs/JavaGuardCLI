import java.util.Optional;

class GuardedByIsPresent {
    String unwrap(Optional<String> value) {
        if (value.isPresent()) {
            return value.get();
        }
        return "default";
    }
}
