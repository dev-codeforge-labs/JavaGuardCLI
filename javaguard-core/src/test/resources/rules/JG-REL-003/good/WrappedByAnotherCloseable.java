import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

class WrappedByAnotherCloseable {
    void read(String path) throws IOException {
        FileReader fr = new FileReader(path);
        try (BufferedReader br = new BufferedReader(fr)) {
            br.readLine();
        }
    }
}
