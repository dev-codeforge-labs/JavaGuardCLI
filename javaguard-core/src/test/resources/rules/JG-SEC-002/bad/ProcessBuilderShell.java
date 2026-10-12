class ProcessBuilderShell {
    void run(String userScript) throws Exception {
        new ProcessBuilder("sh", "-c", userScript).start();
    }
}
