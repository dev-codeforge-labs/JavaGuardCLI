import java.util.concurrent.ConcurrentHashMap;

class ComputeIfAbsentUsed {
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    void register(String key, String value) {
        cache.computeIfAbsent(key, k -> value);
    }
}
