import java.util.List;

class FindAllWithPageable {
    private final UserRepository userRepository;

    FindAllWithPageable(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    List<Object> page(Object pageable) {
        return userRepository.findAll(pageable).stream().filter(u -> u != null).toList();
    }
}

interface UserRepository {
    List<Object> findAll(Object pageable);
}
