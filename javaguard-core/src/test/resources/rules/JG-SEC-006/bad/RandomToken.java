import java.util.Random;

class RandomToken {
    String issue() {
        String token = "T" + new Random().nextInt(1_000_000);
        return token;
    }
}
