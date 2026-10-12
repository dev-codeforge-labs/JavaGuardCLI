import java.security.SecureRandom;

class SecureRandomToken {
    String issue() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        String token = java.util.Base64.getEncoder().encodeToString(bytes);
        return token;
    }
}
