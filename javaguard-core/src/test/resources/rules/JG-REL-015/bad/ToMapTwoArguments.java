import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class ToMapTwoArguments {
    Map<String, Integer> index(List<String> names) {
        return names.stream().collect(Collectors.toMap(n -> n, String::length));
    }
}
