class BroadCatchDoesNothingUseful {
    void run() {
        try {
            risky();
        } catch (Exception e) {
            int x = 1;
        }
    }

    void risky() throws Exception {
    }
}
