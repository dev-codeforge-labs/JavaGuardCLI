import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

class SendRedirectFromParam {
    void go(HttpServletResponse response, String target) throws IOException {
        response.sendRedirect(target);
    }
}
