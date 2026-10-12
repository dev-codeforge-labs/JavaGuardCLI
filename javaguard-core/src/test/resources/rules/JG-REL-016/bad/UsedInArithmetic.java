import java.util.Map;

class UsedInArithmetic {
    int total(Map<String, Integer> counts, String key, int extra) {
        return counts.get(key) * extra;
    }
}
