import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void a() {
        assumeTrue(true);
        assertEquals(1, 1);
        assertEquals(2, 2);
    }
}
