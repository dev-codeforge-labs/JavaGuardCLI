import org.junit.jupiter.api.Test;

class CatchThrowableWithoutAssertionsTest {
    @Test
    void cleanupMayFail() {
        try {
            cleanup();
        } catch (Throwable t) {
            // nothing asserted inside the try block
        }
    }

    private void cleanup() {
    }
}
