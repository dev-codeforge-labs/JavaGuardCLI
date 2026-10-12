import javax.sql.DataSource;

class ChainedFromDataSource {
    private DataSource dataSource;

    void remove(String id) throws Exception {
        dataSource.getConnection().createStatement().executeUpdate("DELETE FROM orders WHERE id = " + id);
    }
}
