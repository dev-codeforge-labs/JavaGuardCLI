import java.util.List;
import java.util.function.Supplier;

class NullInsideNestedLambdaIsUnrelated {
    List<String> items() {
        Supplier<String> supplier = () -> {
            return null;
        };
        return List.of(supplier.get());
    }
}
