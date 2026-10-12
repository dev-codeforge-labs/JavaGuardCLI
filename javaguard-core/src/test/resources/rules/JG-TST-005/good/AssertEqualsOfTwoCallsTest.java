import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AssertEqualsOfTwoCallsTest {
    @Test
    void isDeterministic() {
        assertEquals(Math.abs(-3), Math.abs(-3));
    }
}
