import java.io.FileInputStream;
import java.io.IOException;

class RawFileInputStreamReadLoop {
    void run(FileInputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            System.out.write(b);
        }
    }
}
