import java.util.ArrayList;
import java.util.List;

class SequentialForEachAddsToSharedList {
    List<Integer> process(List<Integer> input) {
        List<Integer> results = new ArrayList<>();
        input.stream().forEach(x -> results.add(x * 2));
        return results;
    }
}
