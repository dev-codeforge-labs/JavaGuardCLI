import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AssertNotNullOfLookupTest {
    @Test
    void lookupFindsTheEntry() {
        Map<String, String> map = Map.of("k", "v");
        assertNotNull(map.get("k"));
    }
}
