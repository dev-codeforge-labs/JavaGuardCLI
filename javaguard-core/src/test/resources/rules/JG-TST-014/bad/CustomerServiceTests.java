import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CustomerServiceTests {
    @Test
    void qualifiedMock() {
        CustomerService service = Mockito.mock(CustomerService.class);
        service.toString();
    }
}

class CustomerService {
}
