import java.util.List;

class RepositoryCallInLoop {
    private final UserRepository userRepository;

    RepositoryCallInLoop(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    void load(List<Long> ids) {
        for (Long id : ids) {
            userRepository.findById(id);
        }
    }
}

interface UserRepository {
    Object findById(Long id);
}
