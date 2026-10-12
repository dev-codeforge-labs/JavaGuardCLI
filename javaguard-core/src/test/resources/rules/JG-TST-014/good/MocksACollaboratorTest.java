import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class MocksACollaboratorTest {
    @Mock
    PaymentGateway gateway;

    @InjectMocks
    MocksACollaborator subject;

    @Test
    void realSubjectMockedCollaborator() {
        subject.hashCode();
    }
}

class MocksACollaborator {
}

class PaymentGateway {
}
