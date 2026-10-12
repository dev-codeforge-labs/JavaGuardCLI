import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class MocksTheClassUnderTestTest {
    @Mock
    MocksTheClassUnderTest subject;

    @Test
    void usesTheMock() {
        subject.hashCode();
    }
}

class MocksTheClassUnderTest {
}
