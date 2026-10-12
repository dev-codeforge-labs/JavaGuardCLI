import static org.mockito.Mockito.mock;

class NotATestClass {
    Object build() {
        return mock(NotATestClass.class);
    }
}
