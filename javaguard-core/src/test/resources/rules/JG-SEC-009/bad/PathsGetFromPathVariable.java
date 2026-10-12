import java.nio.file.Path;
import java.nio.file.Paths;

class PathsGetFromPathVariable {
    Path load(@org.springframework.web.bind.annotation.PathVariable String name) {
        return Paths.get(name);
    }
}
