import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class AssertNotNullOfNewObjectTest {
    @Test
    void constructedObjectIsNotNull() {
        assertNotNull(new StringBuilder());
        assertNotNull("literal");
    }
}
