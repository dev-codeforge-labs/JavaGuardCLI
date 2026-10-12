import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void sums() {
        assertAll(
            () -> assertEquals(1, 1),
            () -> assertEquals(2, 2),
            () -> assertEquals(3, 3));
    }
}
