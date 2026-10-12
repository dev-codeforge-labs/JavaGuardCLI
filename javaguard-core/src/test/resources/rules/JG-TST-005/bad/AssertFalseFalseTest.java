import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class AssertFalseFalseTest {
    @Test
    public void neverFails() {
        assertFalse(false);
    }
}
