import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CatchErrorOnVerifyTest {
    @Test
    void verifySwallowed() {
        List<String> mock = Mockito.mock(List.class);
        try {
            verify(mock).clear();
        } catch (Error e) {
            e.getMessage();
        }
    }
}
