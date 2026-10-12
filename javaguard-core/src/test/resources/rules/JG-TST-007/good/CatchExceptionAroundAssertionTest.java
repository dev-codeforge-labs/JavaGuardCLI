import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class CatchExceptionAroundAssertionTest {
    @Test
    void ioProblemsAreWrapped() {
        try {
            assertTrue(read());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean read() throws IOException {
        return true;
    }
}
