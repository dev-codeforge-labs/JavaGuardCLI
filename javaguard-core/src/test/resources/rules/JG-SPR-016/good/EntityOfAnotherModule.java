import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

@jakarta.persistence.Entity
class Note {
    @jakarta.persistence.Id
    Long id;
}

// fineract RoleRepository: Role is an entity of another module, one letter away from Note
interface EntityOfAnotherModule extends JpaRepository<Role, Long> {
    @Query("SELECT role FROM Role role WHERE LOWER(role.name) = LOWER(:name)")
    Role getRoleByName(String name);
}
