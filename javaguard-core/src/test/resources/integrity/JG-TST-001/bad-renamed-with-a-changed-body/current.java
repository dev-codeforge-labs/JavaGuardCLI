import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void addsNumbers() {
        assertEquals(5, 2 + 3);
        assertEquals(7, 3 + 4);
    }
}
