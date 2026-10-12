import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTemplateInStreamLoop {
    void load(JdbcTemplate jdbc, List<Long> ids) {
        ids.forEach(id -> jdbc.queryForObject("select name from t where id = ?", String.class, id));
    }
}
