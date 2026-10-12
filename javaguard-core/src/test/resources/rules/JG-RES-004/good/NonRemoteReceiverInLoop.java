import java.util.List;
import java.util.Map;

class NonRemoteReceiverInLoop {
    int total(List<String> ids, Map<String, Integer> prices) {
        int sum = 0;
        for (String id : ids) {
            sum += prices.get(id);
        }
        return sum;
    }
}
