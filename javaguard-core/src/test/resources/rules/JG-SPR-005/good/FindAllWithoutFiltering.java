import java.util.List;

class FindAllWithoutFiltering {
    private final UserRepository userRepository;

    FindAllWithoutFiltering(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    List<Object> everything() {
        return userRepository.findAll();
    }

    long total() {
        return userRepository.findAll().stream().count();
    }
}

interface UserRepository {
    List<Object> findAll();
}
