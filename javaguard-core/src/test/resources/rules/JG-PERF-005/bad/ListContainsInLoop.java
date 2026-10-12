import java.util.List;

class ListContainsInLoop {
    int countKnown(List<String> known, List<String> items) {
        int count = 0;
        for (String item : items) {
            if (known.contains(item)) {
                count++;
            }
        }
        return count;
    }
}
