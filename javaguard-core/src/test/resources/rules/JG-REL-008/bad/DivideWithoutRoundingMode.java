import java.math.BigDecimal;

class DivideWithoutRoundingMode {
    BigDecimal split(BigDecimal total, BigDecimal parts) {
        return total.divide(parts);
    }
}
