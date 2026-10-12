import java.security.MessageDigest;

class Sha256ForPassword {
    byte[] hashPassword(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(password.getBytes());
    }
}
