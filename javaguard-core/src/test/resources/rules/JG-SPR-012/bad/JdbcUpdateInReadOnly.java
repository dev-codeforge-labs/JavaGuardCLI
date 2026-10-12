import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class JdbcUpdateInReadOnly {
    private JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public int markRead(long id) {
        return jdbcTemplate.update("update message set read = true where id = ?", id);
    }

    @Transactional(readOnly = true)
    public void purge(String status) {
        messages().deleteByStatus(status);
    }

    private MessageRepository repo;

    private MessageRepository messages() {
        return repo;
    }

    @Transactional(readOnly = true)
    public void purgeDirect(String status) {
        repo.deleteByStatus(status);
    }
}
