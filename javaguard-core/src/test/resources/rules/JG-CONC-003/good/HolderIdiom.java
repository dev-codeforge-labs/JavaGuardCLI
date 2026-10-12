class HolderIdiom {
    private HolderIdiom() {
    }

    private static final class Holder {
        private static final HolderIdiom INSTANCE = new HolderIdiom();
    }

    static HolderIdiom getInstance() {
        return Holder.INSTANCE;
    }
}
