import java.util.HashMap;
import java.util.Map;

class CheckThenActOnPlainHashMap {
    private final Map<String, String> cache = new HashMap<>();

    void register(String key, String value) {
        if (!cache.containsKey(key)) {
            cache.put(key, value);
        }
    }
}
