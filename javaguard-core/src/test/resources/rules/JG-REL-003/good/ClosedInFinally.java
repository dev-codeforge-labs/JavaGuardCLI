import java.io.FileInputStream;
import java.io.IOException;

class ClosedInFinally {
    void read(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        try {
            in.read();
        } finally {
            in.close();
        }
    }
}
