class EmptyCatch {
    void run() {
        try {
            risky();
        } catch (java.io.IOException e) {
        }
    }

    void risky() throws java.io.IOException {
    }
}
