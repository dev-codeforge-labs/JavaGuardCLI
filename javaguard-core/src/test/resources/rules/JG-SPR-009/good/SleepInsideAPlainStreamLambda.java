import java.util.List;

class SleepInsideAPlainStreamLambda {
    void run(List<Integer> input) {
        input.forEach(v -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
