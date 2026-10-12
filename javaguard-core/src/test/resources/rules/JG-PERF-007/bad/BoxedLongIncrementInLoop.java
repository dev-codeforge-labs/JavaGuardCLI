import java.util.List;

class BoxedLongIncrementInLoop {
    long sum(List<Long> values) {
        Long total = 0L;
        for (Long v : values) {
            total += v;
        }
        return total;
    }
}
