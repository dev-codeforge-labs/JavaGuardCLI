import org.springframework.stereotype.Service;

@Service
class ConstructorAssignedOnly {
    private final String label;

    ConstructorAssignedOnly(String label) {
        this.label = label;
    }
}
