import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

class InvoiceServiceTest {
    @MockBean
    InvoiceService invoiceService;

    @Test
    void springMockBean() {
        invoiceService.toString();
    }
}

class InvoiceService {
}
