import javax.net.ssl.HostnameVerifier;

class HostnameVerifierRealCheck {
    HostnameVerifier verifier() {
        return (hostname, session) -> hostname.equals("expected.example.com");
    }
}
