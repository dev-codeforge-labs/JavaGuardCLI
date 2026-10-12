import org.springframework.transaction.annotation.Transactional;

class SwallowedInTransactionalMethod {
    @Transactional
    public void save() {
        try {
            write();
        } catch (RuntimeException e) {
            System.out.println("ignored");
        }
    }

    private void write() {
    }
}
