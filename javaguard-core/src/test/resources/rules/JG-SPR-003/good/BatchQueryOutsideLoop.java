import java.util.List;

class BatchQueryOutsideLoop {
    private final AccountRepository accountRepository;

    BatchQueryOutsideLoop(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    void load(List<Long> ids) {
        List<Object> all = accountRepository.findAllById(ids);
        for (Object each : all) {
            each.hashCode();
        }
    }
}

interface AccountRepository {
    List<Object> findAllById(List<Long> ids);
}
