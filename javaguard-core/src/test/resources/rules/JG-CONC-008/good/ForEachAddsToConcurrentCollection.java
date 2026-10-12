import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class ForEachAddsToConcurrentCollection {
    List<Integer> process(List<Integer> input) {
        CopyOnWriteArrayList<Integer> results = new CopyOnWriteArrayList<>();
        input.parallelStream().forEach(x -> results.add(x * 2));
        return results;
    }
}
