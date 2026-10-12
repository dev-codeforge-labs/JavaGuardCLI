import java.util.concurrent.ConcurrentMap;

class GetNullThenPut {
    void register(ConcurrentMap<String, String> cache, String key, String value) {
        if (cache.get(key) == null) {
            cache.put(key, value);
        }
    }
}
