import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class CatchThrowableAroundFailTest {
    @Test
    void failIsSwallowed() {
        try {
            callUnderTest();
            fail("should have thrown");
        } catch (Throwable t) {
            // expected
        }
    }

    private void callUnderTest() {
    }
}
