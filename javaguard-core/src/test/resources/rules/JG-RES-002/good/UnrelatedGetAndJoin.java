import java.util.List;
import java.util.Map;
import java.util.Optional;

class UnrelatedGetAndJoin {
    String values(Optional<String> name, Map<String, String> map, List<String> list) {
        return name.get() + map.get("a") + list.get(0) + String.join(",", list);
    }
}
