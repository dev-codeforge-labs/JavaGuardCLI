import java.util.List;
import org.springframework.kafka.core.KafkaTemplate;

// fineract KafkaExternalEventProducer; petclinic-microservices ApiGatewayController
class AsyncSendAndReactiveContinuation {
    private KafkaTemplate<Long, byte[]> kafkaTemplate;
    private CustomersServiceClient customersServiceClient;
    private VisitsServiceClient visitsServiceClient;

    void publish(List<byte[]> messages) {
        for (byte[] message : messages) {
            kafkaTemplate.send("topic", 1L, message);
        }
    }

    Object owner(int ownerId) {
        return customersServiceClient.getOwner(ownerId)
                .flatMap(owner -> visitsServiceClient.getVisitsForPets(owner.getPetIds()));
    }
}
