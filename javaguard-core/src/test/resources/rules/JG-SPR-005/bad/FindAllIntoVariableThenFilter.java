import java.util.List;

class FindAllIntoVariableThenFilter {
    private final UserRepository userRepository;

    FindAllIntoVariableThenFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    long count() {
        List<Object> all = userRepository.findAll();
        return all.stream().filter(u -> u.hashCode() > 0).count();
    }
}

interface UserRepository {
    List<Object> findAll();
}
