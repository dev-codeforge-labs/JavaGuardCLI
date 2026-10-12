import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void fails() {
        try {
            run();
            fail("expected");
        } catch (IllegalStateException e) {
            assertNotNull(e);
        }
    }
}
