import java.io.FileInputStream;
import java.io.IOException;

class RawStreamReadOutsideLoop {
    int first(FileInputStream in) throws IOException {
        return in.read();
    }
}
