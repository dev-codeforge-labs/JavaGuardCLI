import java.util.List;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;

// fineract: rowSet.next() is the emptiness check; CollectionUtils.isEmpty; get(0) in a try that catches the empty case
class RowSetsHelpersAndHandledEmptiness {
    private JdbcTemplate jdbcTemplate;
    private BalanceRepository balanceRepository;

    String first() {
        SqlRowSet rs = jdbcTemplate.queryForRowSet("select name from t");
        if (rs.next()) {
            return rs.getString("name");
        }
        return null;
    }

    java.math.BigDecimal last(long id) {
        List<java.math.BigDecimal> data = balanceRepository.findLastClosingBalance(id);
        return CollectionUtils.isEmpty(data) ? java.math.BigDecimal.ZERO : data.getFirst();
    }

    Object one(long id) {
        List<Object> rows = balanceRepository.findRows(id);
        try {
            return rows.get(0);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalStateException("not found", e);
        }
    }
}
