import java.util.concurrent.atomic.AtomicInteger;

class AtomicIntegerIncremented {
    private static final AtomicInteger counter = new AtomicInteger();

    void increment() {
        counter.incrementAndGet();
    }
}
