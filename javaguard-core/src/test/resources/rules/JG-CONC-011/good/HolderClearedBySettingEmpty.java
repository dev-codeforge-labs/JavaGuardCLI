import java.util.Optional;

// fineract BatchRequestContextHolder
class HolderClearedBySettingEmpty {
    private static final ThreadLocal<Optional<String>> tx = ThreadLocal.withInitial(Optional::empty);
    private static final ThreadLocal<String> user = new ThreadLocal<>();

    static void setTx(String t) {
        tx.set(Optional.ofNullable(t));
    }

    static void clearTx() {
        tx.set(Optional.empty());
    }

    static void setUser(String u) {
        user.set(u);
    }

    static void clearUser() {
        user.set(null);
    }
}
