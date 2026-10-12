class NotAFactory {
    Object newInstance(String name) {
        return name.toUpperCase();
    }

    void run() {
        newInstance("x");
    }
}
