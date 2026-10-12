import java.util.ArrayList;
import java.util.List;

class ForEachAddsToSharedList {
    List<Integer> process(List<Integer> input) {
        List<Integer> results = new ArrayList<>();
        input.parallelStream().forEach(x -> results.add(x * 2));
        return results;
    }
}
