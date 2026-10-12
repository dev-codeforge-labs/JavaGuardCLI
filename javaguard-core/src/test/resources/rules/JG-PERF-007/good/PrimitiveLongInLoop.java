import java.util.List;

class PrimitiveLongInLoop {
    long sum(List<Long> values) {
        long total = 0L;
        for (Long v : values) {
            total += v;
        }
        return total;
    }
}
