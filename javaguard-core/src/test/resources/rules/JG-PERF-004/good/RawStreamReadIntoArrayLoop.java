import java.io.FileInputStream;
import java.io.IOException;

class RawStreamReadIntoArrayLoop {
    void run(FileInputStream in) throws IOException {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            System.out.write(buffer, 0, n);
        }
    }
}
