import java.util.List;
import java.util.regex.Pattern;

class PatternCompiledOutsideLoop {
    void run(List<String> lines) {
        Pattern p = Pattern.compile("^[a-z]+\\d+$");
        for (String line : lines) {
            p.matcher(line).matches();
        }
    }
}
