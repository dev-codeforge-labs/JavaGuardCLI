import org.springframework.cache.annotation.Cacheable;

class StaticCacheable {
    @Cacheable("users")
    public static String find(String id) {
        return id;
    }
}
