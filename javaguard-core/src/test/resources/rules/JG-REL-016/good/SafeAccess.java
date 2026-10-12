import java.util.Map;

class SafeAccess {
    int withDefault(Map<String, Integer> counts, String key) {
        int n = counts.getOrDefault(key, 0);
        return n;
    }

    int checked(Map<String, Integer> counts, String key) {
        if (!counts.containsKey(key)) {
            return 0;
        }
        int n = counts.get(key);
        return n;
    }

    Integer boxed(Map<String, Integer> counts, String key) {
        Integer n = counts.get(key);
        return counts.get(key);
    }

    String text(Map<String, String> names, String key) {
        return names.get(key);
    }
}
