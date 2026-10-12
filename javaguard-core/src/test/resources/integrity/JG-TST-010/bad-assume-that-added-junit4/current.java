import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void a() {
        Assume.assumeNotNull(System.getenv("HOME"));
        assertEquals(1, 1);
    }
}
