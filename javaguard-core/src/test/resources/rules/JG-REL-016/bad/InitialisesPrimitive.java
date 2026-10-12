import java.util.Map;

class InitialisesPrimitive {
    private final Map<String, Integer> stock;

    InitialisesPrimitive(Map<String, Integer> stock) {
        this.stock = stock;
    }

    int available(String sku) {
        int quantity = stock.get(sku);
        return quantity;
    }
}
