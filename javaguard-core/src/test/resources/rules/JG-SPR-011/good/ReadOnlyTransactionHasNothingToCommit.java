import java.io.IOException;
import org.springframework.transaction.annotation.Transactional;

// fineract LoanCOBFilterHelperImpl.calculateRelevantLoanIds
class ReadOnlyTransactionHasNothingToCommit {
    @Transactional(readOnly = true)
    public java.util.List<Long> ids(java.io.InputStream body) throws IOException {
        return java.util.List.of();
    }
}
