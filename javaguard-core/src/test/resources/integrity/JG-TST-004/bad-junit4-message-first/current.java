import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.*;

class SampleTest {
    @Test
    void adds() {
        Calc calc = new Calc();
        assertEquals("sum", 6, calc.add(2, 3));
    }
}
