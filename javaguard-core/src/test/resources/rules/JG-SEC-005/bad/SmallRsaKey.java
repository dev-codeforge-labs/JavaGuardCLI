import java.security.KeyPairGenerator;

class SmallRsaKey {
    KeyPairGenerator make() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        return kpg;
    }
}
