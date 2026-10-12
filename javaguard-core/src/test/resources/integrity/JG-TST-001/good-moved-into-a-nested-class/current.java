import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Nested
    class Arithmetic {
        @Test
        void adds() {
            assertEquals(5, 2 + 3);
        }
    }
}
