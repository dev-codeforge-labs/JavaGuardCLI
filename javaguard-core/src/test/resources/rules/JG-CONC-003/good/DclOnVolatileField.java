class DclOnVolatileField {
    private static volatile DclOnVolatileField instance;

    static DclOnVolatileField getInstance() {
        if (instance == null) {
            synchronized (DclOnVolatileField.class) {
                if (instance == null) {
                    instance = new DclOnVolatileField();
                }
            }
        }
        return instance;
    }
}
