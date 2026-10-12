import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class ToMapWithMerge {
    Map<String, Integer> merged(List<String> names) {
        return names.stream().collect(Collectors.toMap(n -> n, String::length, (a, b) -> a));
    }

    Map<String, Integer> sorted(List<String> names) {
        return names.stream().collect(Collectors.toMap(n -> n, String::length, (a, b) -> a, LinkedHashMap::new));
    }

    Map<String, List<String>> grouped(List<String> names) {
        return names.stream().collect(Collectors.groupingBy(n -> n));
    }
}
