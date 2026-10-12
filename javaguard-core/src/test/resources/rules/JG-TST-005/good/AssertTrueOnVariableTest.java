import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AssertTrueOnVariableTest {
    @Test
    void usesAComputedFlag() {
        boolean ok = "abc".startsWith("a");
        assertTrue(ok);
    }
}
