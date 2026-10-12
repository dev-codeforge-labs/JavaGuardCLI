import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void a() {
        assertTimeout(Duration.ofMinutes(10), () -> {});
    }
}
