import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity
class EventConfiguration {
    @Id
    String type;
}

interface CustomEventConfigurationRepository {
    EventConfiguration findByTypeWithNotFoundDetection(String type);
}

// fineract ExternalEventConfigurationRepository: the method comes from a custom fragment implemented by ...Impl
interface CustomFragmentMethod extends JpaRepository<EventConfiguration, String>, CustomEventConfigurationRepository {
    @Override
    EventConfiguration findByTypeWithNotFoundDetection(String type);
}
