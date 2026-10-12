class WhileLoopWithRealWork {
    void process(java.util.Queue<String> queue) throws InterruptedException {
        while (!queue.isEmpty()) {
            String item = queue.poll();
            System.out.println(item);
            Thread.sleep(50);
        }
    }
}
