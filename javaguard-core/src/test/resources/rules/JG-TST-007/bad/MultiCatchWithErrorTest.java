import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MultiCatchWithErrorTest {
    @Test
    void multiCatch() {
        try {
            assertTrue(compute());
        } catch (AssertionError | IllegalStateException e) {
            return;
        }
    }

    private boolean compute() {
        return false;
    }
}
