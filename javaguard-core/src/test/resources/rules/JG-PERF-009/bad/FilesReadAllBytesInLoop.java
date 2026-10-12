import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class FilesReadAllBytesInLoop {
    void run(List<Path> files) throws java.io.IOException {
        for (Path f : files) {
            byte[] content = Files.readAllBytes(f);
            System.out.println(content.length);
        }
    }
}
