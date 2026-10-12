import java.util.Random;

class RandomForNonSecretName {
    int diceRoll() {
        int roll = new Random().nextInt(6) + 1;
        return roll;
    }
}
