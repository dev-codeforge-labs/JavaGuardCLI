import org.springframework.transaction.annotation.Transactional;

@Transactional
class SwallowedInTransactionalClass {
    public void save() {
        try {
            write();
        } catch (Exception e) {
            // best effort
        }
    }

    private void write() {
    }
}
