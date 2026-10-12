import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

class JdbcInsideReactiveLambda {
    Mono<String> run(JdbcTemplate jdbc, Mono<Long> ids) {
        return ids.map(id -> jdbc.queryForObject("select name from t where id = ?", String.class, id));
    }
}
