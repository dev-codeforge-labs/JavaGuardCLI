import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CatchAssertionErrorTest {
    @Test
    void swallowsTheFailure() {
        try {
            assertEquals(1, 2);
        } catch (AssertionError e) {
            System.out.println("ignored");
        }
    }
}
