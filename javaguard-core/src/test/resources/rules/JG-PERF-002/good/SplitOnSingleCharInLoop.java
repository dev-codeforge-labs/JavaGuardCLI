import java.util.List;

class SplitOnSingleCharInLoop {
    void run(List<String> lines) {
        for (String line : lines) {
            String[] parts = line.split(",");
            System.out.println(parts.length);
        }
    }
}
