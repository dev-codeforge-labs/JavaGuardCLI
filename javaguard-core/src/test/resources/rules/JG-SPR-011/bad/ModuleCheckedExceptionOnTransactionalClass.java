import org.springframework.transaction.annotation.Transactional;

class InsufficientFundsException extends Exception {
}

@Transactional
class ModuleCheckedExceptionOnTransactionalClass {
    public void transfer(long from, long to, long amount) throws InsufficientFundsException {
        throw new InsufficientFundsException();
    }
}
