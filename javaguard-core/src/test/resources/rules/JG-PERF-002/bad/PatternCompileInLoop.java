import java.util.List;
import java.util.regex.Pattern;

class PatternCompileInLoop {
    void run(List<String> lines) {
        for (String line : lines) {
            Pattern p = Pattern.compile("^[a-z]+\\d+$");
            p.matcher(line).matches();
        }
    }
}
