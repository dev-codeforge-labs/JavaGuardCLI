import java.net.HttpURLConnection;
import java.net.URL;

class UrlConnectionWithBothTimeouts {
    int status(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(5000);
        return connection.getResponseCode();
    }

    java.net.URLConnection passedOn(URL url) throws Exception {
        return url.openConnection();
    }
}
