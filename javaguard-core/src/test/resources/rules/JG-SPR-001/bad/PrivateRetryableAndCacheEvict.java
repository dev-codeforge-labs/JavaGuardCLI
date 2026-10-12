import org.springframework.cache.annotation.CacheEvict;
import org.springframework.retry.annotation.Retryable;

class PrivateRetryableAndCacheEvict {
    @Retryable
    private void call() {
    }

    @CacheEvict("users")
    private void evict() {
    }
}
