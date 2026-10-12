import java.io.FileInputStream;
import java.io.IOException;

class ReturnedToCaller {
    FileInputStream open(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        return in;
    }
}
