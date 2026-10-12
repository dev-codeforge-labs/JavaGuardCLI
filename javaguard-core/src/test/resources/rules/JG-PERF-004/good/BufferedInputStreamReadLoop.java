import java.io.BufferedInputStream;
import java.io.IOException;

class BufferedInputStreamReadLoop {
    void run(BufferedInputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            System.out.write(b);
        }
    }
}
