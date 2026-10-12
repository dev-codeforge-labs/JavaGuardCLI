class SynchronizedMethodIncrement {
    private static int counter;

    synchronized void increment() {
        counter++;
    }
}
