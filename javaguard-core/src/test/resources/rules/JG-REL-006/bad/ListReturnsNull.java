import java.util.List;

class ListReturnsNull {
    List<String> items(boolean has) {
        if (!has) {
            return null;
        }
        return List.of("x");
    }
}
