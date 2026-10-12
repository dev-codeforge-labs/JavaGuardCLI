import java.io.InputStream;
import java.util.List;

class InputStreamReadAllBytesInLoop {
    void run(List<InputStream> streams) throws java.io.IOException {
        for (InputStream in : streams) {
            byte[] content = in.readAllBytes();
            System.out.println(content.length);
        }
    }
}
