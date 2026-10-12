import java.util.concurrent.CompletableFuture;

class MainMayWait {
    public static void main(String[] args) {
        CompletableFuture<Void> work = CompletableFuture.runAsync(() -> System.out.println("done"));
        work.join();
    }
}
