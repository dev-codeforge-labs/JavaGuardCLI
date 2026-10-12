import java.util.Map;
import java.util.stream.Collectors;

class MapValuesAreNotEnumValues {
    Map<String, Customer> byEmail(Map<Long, Customer> customers) {
        return customers.values().stream().collect(Collectors.toMap(Customer::getEmail, c -> c));
    }
}
