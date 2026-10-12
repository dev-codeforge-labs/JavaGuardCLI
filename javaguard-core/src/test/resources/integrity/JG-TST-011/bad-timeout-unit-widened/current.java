import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void a() {
        assertEquals(1, 1);
    }
}
