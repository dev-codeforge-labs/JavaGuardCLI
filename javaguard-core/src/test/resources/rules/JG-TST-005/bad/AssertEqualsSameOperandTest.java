import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AssertEqualsSameOperandTest {
    @Test
    void comparesAValueToItself() {
        int result = 42;
        assertEquals(result, result);
    }
}
