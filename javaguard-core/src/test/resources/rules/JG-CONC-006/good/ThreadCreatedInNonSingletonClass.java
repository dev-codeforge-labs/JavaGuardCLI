class ThreadCreatedInNonSingletonClass {
    void process() {
        new Thread(() -> System.out.println("work")).start();
    }
}
