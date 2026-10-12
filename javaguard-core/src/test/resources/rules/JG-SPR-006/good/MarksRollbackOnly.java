import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

class MarksRollbackOnly {
    @Transactional
    public void save() {
        try {
            write();
        } catch (RuntimeException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private void write() {
    }
}
