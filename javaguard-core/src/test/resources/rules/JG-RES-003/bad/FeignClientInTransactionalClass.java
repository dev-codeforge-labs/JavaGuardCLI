import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class FeignClientInTransactionalClass {
    private final PaymentClient payments;

    FeignClientInTransactionalClass(PaymentClient payments) {
        this.payments = payments;
    }

    void charge(String orderId) {
        this.payments.charge(orderId);
    }
}
