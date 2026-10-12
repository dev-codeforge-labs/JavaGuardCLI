import java.util.ArrayList;
import java.util.List;

class FindAllThenLoopWithIf {
    private final UserRepository userRepository;

    FindAllThenLoopWithIf(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    List<Object> pick() {
        List<Object> picked = new ArrayList<>();
        for (Object u : userRepository.findAll()) {
            if (u.hashCode() > 3) {
                picked.add(u);
            }
        }
        return picked;
    }
}

interface UserRepository {
    List<Object> findAll();
}
