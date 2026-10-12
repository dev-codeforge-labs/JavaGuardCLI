class DirectProgramNoShell {
    void run(String file) throws Exception {
        new ProcessBuilder("cat", file).start();
    }
}
