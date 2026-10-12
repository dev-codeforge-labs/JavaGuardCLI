class ClassicDclNonVolatile {
    private static ClassicDclNonVolatile instance;

    static ClassicDclNonVolatile getInstance() {
        if (instance == null) {
            synchronized (ClassicDclNonVolatile.class) {
                if (instance == null) {
                    instance = new ClassicDclNonVolatile();
                }
            }
        }
        return instance;
    }
}
