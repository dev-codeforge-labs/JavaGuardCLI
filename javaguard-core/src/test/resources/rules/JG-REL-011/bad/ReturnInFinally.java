class ReturnInFinally {
    int run() {
        try {
            return risky();
        } finally {
            return -1;
        }
    }

    int risky() {
        throw new RuntimeException("boom");
    }
}
