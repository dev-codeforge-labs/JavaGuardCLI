import java.io.InputStream;
import java.util.List;

class BufferedReadInLoop {
    void run(List<InputStream> streams) throws java.io.IOException {
        byte[] buffer = new byte[8192];
        for (InputStream in : streams) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                System.out.write(buffer, 0, n);
            }
        }
    }
}
