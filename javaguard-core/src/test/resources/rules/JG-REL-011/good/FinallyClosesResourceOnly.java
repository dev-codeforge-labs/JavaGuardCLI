class FinallyClosesResourceOnly {
    int run(java.io.Closeable resource) throws Exception {
        try {
            return risky();
        } finally {
            resource.close();
        }
    }

    int risky() {
        return 1;
    }
}
