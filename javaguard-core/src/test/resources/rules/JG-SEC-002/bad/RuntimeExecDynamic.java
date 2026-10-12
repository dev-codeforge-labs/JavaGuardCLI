class RuntimeExecDynamic {
    void run(String userInput) throws Exception {
        String cmd = "ping " + userInput;
        Runtime.getRuntime().exec(cmd);
    }
}
