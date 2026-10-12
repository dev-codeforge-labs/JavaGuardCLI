import javax.crypto.Cipher;

class StrongCipherGcm {
    Cipher init() throws Exception {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }
}
