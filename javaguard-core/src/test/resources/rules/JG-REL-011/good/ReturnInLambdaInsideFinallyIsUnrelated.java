import java.util.function.Supplier;

class ReturnInLambdaInsideFinallyIsUnrelated {
    int run() {
        try {
            return risky();
        } finally {
            Supplier<Integer> s = () -> {
                return 42;
            };
            System.out.println(s.get());
        }
    }

    int risky() {
        return 1;
    }
}
