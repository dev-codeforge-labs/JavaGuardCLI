import java.net.URL;
import java.net.URLConnection;

class UrlConnectionOnlyConnectTimeout {
    void open(URL url) throws Exception {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(1000);
        connection.connect();
    }
}
