import org.springframework.transaction.annotation.Transactional;

class RethrowsInsideTransaction {
    @Transactional
    public void save() {
        try {
            write();
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write() {
    }
}
