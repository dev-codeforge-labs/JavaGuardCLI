import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

@Service
class ExecutorCreatedInBeanFactoryMethod {
    @Bean
    ExecutorService taskExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
