import org.springframework.stereotype.Service;

@Service
class SingletonBeanFieldPlusEquals {
    private int total;

    void add(int amount) {
        total += amount;
    }
}
