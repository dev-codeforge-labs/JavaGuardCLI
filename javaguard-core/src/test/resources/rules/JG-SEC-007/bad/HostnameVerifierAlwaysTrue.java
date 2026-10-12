import javax.net.ssl.HostnameVerifier;

class HostnameVerifierAlwaysTrue {
    HostnameVerifier verifier() {
        return (hostname, session) -> true;
    }
}
