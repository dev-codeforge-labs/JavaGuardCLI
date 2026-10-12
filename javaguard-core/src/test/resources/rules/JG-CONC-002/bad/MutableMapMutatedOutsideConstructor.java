import java.util.HashMap;
import org.springframework.stereotype.Component;

@Component
class MutableMapMutatedOutsideConstructor {
    private final HashMap<String, String> cache = new HashMap<>();

    void remember(String key, String value) {
        cache.put(key, value);
    }
}
