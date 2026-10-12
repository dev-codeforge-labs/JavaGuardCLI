import java.security.KeyPairGenerator;

class LargeRsaKey {
    KeyPairGenerator make() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(3072);
        return kpg;
    }
}
