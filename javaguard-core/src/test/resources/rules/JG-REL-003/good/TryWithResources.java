import java.io.FileInputStream;
import java.io.IOException;

class TryWithResources {
    void read(String path) throws IOException {
        try (FileInputStream in = new FileInputStream(path)) {
            in.read();
        }
    }
}
