import java.util.List;

class UnrelatedCallInLoop {
    void load(List<String> names, StringBuilder builder) {
        for (String name : names) {
            builder.append(name);
        }
    }
}
