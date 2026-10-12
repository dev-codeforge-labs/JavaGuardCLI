import javax.crypto.spec.IvParameterSpec;
import java.security.SecureRandom;

class RandomIv {
    IvParameterSpec iv() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return new IvParameterSpec(bytes);
    }
}
