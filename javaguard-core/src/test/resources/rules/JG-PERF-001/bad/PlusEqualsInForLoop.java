import java.util.List;

class PlusEqualsInForLoop {
    String join(List<String> items) {
        String result = "";
        for (String item : items) {
            result += item;
        }
        return result;
    }
}
