import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RethrowsTheErrorTest {
    @Test
    void logsThenRethrows() {
        try {
            assertEquals(1, 1);
        } catch (AssertionError e) {
            System.out.println("context");
            throw e;
        }
    }
}
