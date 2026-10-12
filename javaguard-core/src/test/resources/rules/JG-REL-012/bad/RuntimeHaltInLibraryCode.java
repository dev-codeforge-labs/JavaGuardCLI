class RuntimeHaltInLibraryCode {
    void abort() {
        Runtime.getRuntime().halt(1);
    }
}
