class BreakInFinallyInsideLoop {
    void run(java.util.List<String> items) {
        for (String item : items) {
            try {
                process(item);
            } finally {
                break;
            }
        }
    }

    void process(String item) {
    }
}
