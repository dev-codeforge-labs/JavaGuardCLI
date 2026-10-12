import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RealAssertionsTest {
    @Test
    void checksRealBehavior() {
        List<Integer> list = List.of();
        assertTrue(list.isEmpty());
        assertEquals(3, 1 + 2);
        assertNotNull(java.util.Optional.of("x").get());
    }
}
