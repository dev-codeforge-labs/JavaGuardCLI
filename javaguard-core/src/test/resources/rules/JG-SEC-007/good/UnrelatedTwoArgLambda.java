import java.util.function.BiPredicate;

class UnrelatedTwoArgLambda {
    BiPredicate<Integer, Integer> greater() {
        return (a, b) -> a > b;
    }
}
