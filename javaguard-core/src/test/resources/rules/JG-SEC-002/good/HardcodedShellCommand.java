class HardcodedShellCommand {
    void run() throws Exception {
        new ProcessBuilder("sh", "-c", "echo hello").start();
    }
}
