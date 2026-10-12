import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

// fineract SavingsAccrualWritePlatformServiceImpl, GenericDataServiceImpl, FineractOidcJwtAuthenticationConverter
class CauseKeptElsewhere {
    private Logger log;
    private final List<Throwable> errors = new ArrayList<>();

    void a() {
        try {
            work();
        } catch (Exception e) {
            log.error("Failed for {} : {}", "account", e.getMessage());
            errors.add(e.getCause());
        }
    }

    void b() {
        try {
            work();
        } catch (IllegalStateException e) {
            log.error("Reporting error: {}", e.getMessage());
            throw new IllegalArgumentException("wrapped", e);
        }
    }

    void c() {
        try {
            work();
        } catch (IllegalStateException e) {
            log.warn("Expected condition: {}", e.getMessage());
        }
    }

    void work() {
    }
}
