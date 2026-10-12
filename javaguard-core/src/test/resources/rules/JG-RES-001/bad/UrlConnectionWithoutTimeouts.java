import java.net.HttpURLConnection;
import java.net.URL;

class UrlConnectionWithoutTimeouts {
    int status(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        return connection.getResponseCode();
    }
}
