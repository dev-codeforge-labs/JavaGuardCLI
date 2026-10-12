import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
class CacheableSelfCall {
    public String describe(String id) {
        return lookup(id) + "!";
    }

    @Cacheable("things")
    public String lookup(String id) {
        return id;
    }
}
