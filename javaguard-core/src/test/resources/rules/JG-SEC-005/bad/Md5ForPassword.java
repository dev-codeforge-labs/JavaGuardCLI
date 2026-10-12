import java.security.MessageDigest;

class Md5ForPassword {
    byte[] hashPassword(String password) throws Exception {
        MessageDigest passwordDigest = MessageDigest.getInstance("MD5");
        return passwordDigest.digest(password.getBytes());
    }
}
