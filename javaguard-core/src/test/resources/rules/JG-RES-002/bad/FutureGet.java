import java.util.concurrent.Future;

class FutureGet {
    String await(Future<String> result) throws Exception {
        return result.get();
    }
}
