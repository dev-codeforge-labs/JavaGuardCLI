import java.util.List;

class FreshVariableEachIteration {
    void printUpper(List<String> items) {
        for (String item : items) {
            String upper = item + "!";
            System.out.println(upper);
        }
    }
}
