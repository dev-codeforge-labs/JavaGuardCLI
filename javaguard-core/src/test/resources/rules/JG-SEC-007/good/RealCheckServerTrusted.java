import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

class RealCheckServerTrusted {
    X509TrustManager delegate;

    X509TrustManager wrap() {
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                delegate.checkClientTrusted(chain, authType);
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                delegate.checkServerTrusted(chain, authType);
            }

            public X509Certificate[] getAcceptedIssuers() {
                return delegate.getAcceptedIssuers();
            }
        };
    }
}
