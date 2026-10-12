import java.util.concurrent.Executors;

class PoolInMain {
    public static void main(String[] args) {
        Executors.newCachedThreadPool().shutdown();
    }
}
