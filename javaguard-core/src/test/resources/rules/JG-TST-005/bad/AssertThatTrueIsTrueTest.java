import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssertThatTrueIsTrueTest {
    @Test
    void alwaysGreen() {
        assertThat(true).isTrue();
    }
}
