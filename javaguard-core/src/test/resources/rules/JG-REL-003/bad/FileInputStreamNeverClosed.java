import java.io.FileInputStream;
import java.io.IOException;

class FileInputStreamNeverClosed {
    void read(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        in.read();
    }
}
