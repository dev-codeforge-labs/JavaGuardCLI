import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// fineract enum lookups and maps keyed by primary key
enum Status {
    NEW(1), DONE(2);

    private static final Map<Integer, Status> BY_ID = Arrays.stream(values()).collect(Collectors.toMap(Status::getValue, v -> v));
    private final int value;

    Status(int value) {
        this.value = value;
    }

    int getValue() {
        return value;
    }
}

class UniqueOrConstantKeys {
    Map<Long, Office> byId(List<Office> offices) {
        return offices.stream().collect(Collectors.toMap(Office::getId, Function.identity()));
    }

    Map<Long, Office> byIdLambda(List<Office> offices) {
        return offices.stream().collect(Collectors.toMap(o -> o.getId(), o -> o));
    }

    Map<String, String> copy(Map<String, String> map) {
        return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    Map<Status, String> names() {
        return Arrays.stream(Status.values()).collect(Collectors.toMap(s -> s, Enum::name));
    }
}
