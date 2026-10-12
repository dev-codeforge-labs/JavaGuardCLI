import java.util.List;

class StringMatchesInLoop {
    void run(List<String> lines) {
        for (String line : lines) {
            if (line.matches("[a-z]+\\d+")) {
                System.out.println(line);
            }
        }
    }
}
