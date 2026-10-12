import java.util.HashMap;
import java.util.Map;

class ReturnedAsPrimitive {
    long price(String sku) {
        Map<String, Long> prices = new HashMap<>();
        return prices.get(sku);
    }
}
