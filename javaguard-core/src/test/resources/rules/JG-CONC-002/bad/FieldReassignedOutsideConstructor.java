import org.springframework.stereotype.Service;

@Service
class FieldReassignedOutsideConstructor {
    private String currentUser;

    void setCurrentUser(String user) {
        this.currentUser = user;
    }
}
