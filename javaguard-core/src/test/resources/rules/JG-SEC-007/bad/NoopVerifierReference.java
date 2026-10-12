import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;

class NoopVerifierReference {
    NoopHostnameVerifier verifier() {
        return new NoopHostnameVerifier();
    }
}
