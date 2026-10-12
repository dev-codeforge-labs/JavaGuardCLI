import java.util.HashMap;
import java.util.List;
import java.util.Map;

class MapPeekPutsToSharedMap {
    void process(List<String> input) {
        Map<String, Integer> lengths = new HashMap<>();
        input.stream().parallel().peek(s -> lengths.put(s, s.length())).forEach(s -> {
        });
    }
}
