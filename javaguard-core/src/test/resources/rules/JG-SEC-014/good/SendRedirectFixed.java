import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

class SendRedirectFixed {
    void go(HttpServletResponse response) throws IOException {
        response.sendRedirect("/home");
    }
}
