import java.io.InputStream;
import java.net.URL;

class UrlConnectionChained {
    InputStream open(URL url) throws Exception {
        return url.openConnection().getInputStream();
    }
}
