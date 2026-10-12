import java.util.concurrent.ConcurrentHashMap;

class PutIfAbsentUsed {
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    void register(String key, String value) {
        cache.putIfAbsent(key, value);
    }
}
