import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @ParameterizedTest
    @ValueSource(ints = {2})
    void doubles(int n) {
        assertEquals(4, 2 * n);
    }
}
