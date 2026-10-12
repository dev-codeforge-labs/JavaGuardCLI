import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class ClassicExpectedExceptionTest {
    @Test
    void expectsASpecificException() {
        try {
            callUnderTest();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // fine: fail() throws AssertionError, which this catch does not match
        }
    }

    private void callUnderTest() {
        throw new IllegalStateException();
    }
}
