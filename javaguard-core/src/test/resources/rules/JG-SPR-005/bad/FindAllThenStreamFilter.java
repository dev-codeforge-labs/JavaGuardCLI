import java.util.List;

class FindAllThenStreamFilter {
    private final UserRepository userRepository;

    FindAllThenStreamFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    List<Object> active() {
        return userRepository.findAll().stream().filter(u -> u != null).toList();
    }
}

interface UserRepository {
    List<Object> findAll();
}
