import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class FilesReadAllBytesOutsideLoop {
    void run(Path config, List<String> names) throws java.io.IOException {
        byte[] content = Files.readAllBytes(config);
        for (String name : names) {
            System.out.println(name + ":" + content.length);
        }
    }
}
