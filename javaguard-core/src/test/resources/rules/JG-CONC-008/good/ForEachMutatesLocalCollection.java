import java.util.ArrayList;
import java.util.List;

class ForEachMutatesLocalCollection {
    List<Integer> process(List<Integer> input) {
        return input.parallelStream().map(x -> {
            List<Integer> local = new ArrayList<>();
            local.add(x);
            return local.size();
        }).map(x -> x).collect(java.util.stream.Collectors.toList());
    }
}
