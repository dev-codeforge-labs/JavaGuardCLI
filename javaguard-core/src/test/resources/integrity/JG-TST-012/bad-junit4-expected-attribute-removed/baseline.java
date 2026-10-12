import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test(expected = IllegalStateException.class)
    void fails() {
        run();
    }
}
