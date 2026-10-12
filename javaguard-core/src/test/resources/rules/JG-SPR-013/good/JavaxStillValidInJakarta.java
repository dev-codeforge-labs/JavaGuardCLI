import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.sql.DataSource;
import javax.net.ssl.SSLContext;

class JavaxStillValidInJakarta {
    private DataSource dataSource;
    private Cipher cipher;
    private SSLContext context;

    @PostConstruct
    void init() {
    }
}
