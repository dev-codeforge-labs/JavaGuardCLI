import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void adds() {
        assertEquals(5, 2 + 3);
    }

    int helper() {
        return 1;
    }
}
