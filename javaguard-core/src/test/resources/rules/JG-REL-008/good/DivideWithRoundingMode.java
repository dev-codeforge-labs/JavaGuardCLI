import java.math.BigDecimal;
import java.math.RoundingMode;

class DivideWithRoundingMode {
    BigDecimal split(BigDecimal total, BigDecimal parts) {
        return total.divide(parts, RoundingMode.HALF_UP);
    }
}
