import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class DifferentNameTest {
    @Test
    void mockTypeIsNotTheNamedClass() {
        Object other = mock(OrderService.class);
        other.toString();
    }
}

class OrderService {
}
