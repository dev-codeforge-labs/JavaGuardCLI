import java.util.concurrent.BlockingQueue;

class QueueTake {
    private final BlockingQueue<String> jobs;

    QueueTake(BlockingQueue<String> jobs) {
        this.jobs = jobs;
    }

    String next() throws InterruptedException {
        return this.jobs.take();
    }
}
