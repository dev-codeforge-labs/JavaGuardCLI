import java.util.List;
import java.util.Set;

class HashSetContainsInLoop {
    int countKnown(Set<String> known, List<String> items) {
        int count = 0;
        for (String item : items) {
            if (known.contains(item)) {
                count++;
            }
        }
        return count;
    }
}
