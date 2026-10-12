import java.util.List;

class BoxedIntegerPlusPlusInLoop {
    int count(List<String> items) {
        Integer count = 0;
        for (String item : items) {
            count++;
        }
        return count;
    }
}
