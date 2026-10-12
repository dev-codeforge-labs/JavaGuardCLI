import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    @Timeout(10)
    void a() {
        assertEquals(1, 1);
    }
}
