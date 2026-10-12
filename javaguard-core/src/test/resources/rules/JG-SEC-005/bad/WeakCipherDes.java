import javax.crypto.Cipher;

class WeakCipherDes {
    Cipher init() throws Exception {
        return Cipher.getInstance("DES/CBC/PKCS5Padding");
    }
}
