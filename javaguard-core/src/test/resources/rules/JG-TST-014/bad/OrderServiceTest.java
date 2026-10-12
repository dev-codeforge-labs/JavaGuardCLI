import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class OrderServiceTest {
    @Test
    void mocksTheService() {
        OrderService service = mock(OrderService.class);
        service.toString();
    }
}

class OrderService {
}
