import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void a() {
        assertEquals(1, 1);
    }

    @Test
    @Disabled
    void b() {
        assertEquals(2, 2);
    }
}
