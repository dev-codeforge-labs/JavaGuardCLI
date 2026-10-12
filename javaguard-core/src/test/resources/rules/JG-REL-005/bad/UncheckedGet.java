import java.util.Optional;

class UncheckedGet {
    String unwrap(Optional<String> value) {
        return value.get();
    }
}
