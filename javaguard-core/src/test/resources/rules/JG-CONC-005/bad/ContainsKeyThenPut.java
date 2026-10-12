import java.util.concurrent.ConcurrentHashMap;

class ContainsKeyThenPut {
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    void register(String key, String value) {
        if (!cache.containsKey(key)) {
            cache.put(key, value);
        }
    }
}
