import java.util.List;

class CallOutsideLoop {
    private final CatalogClient catalog;

    CallOutsideLoop(CatalogClient catalog) {
        this.catalog = catalog;
    }

    void enrich() {
        for (String id : catalog.allIds()) {
            System.out.println(id);
        }
        List<String> all = catalog.findAll();
        for (String item : all) {
            System.out.println(item);
        }
    }
}
