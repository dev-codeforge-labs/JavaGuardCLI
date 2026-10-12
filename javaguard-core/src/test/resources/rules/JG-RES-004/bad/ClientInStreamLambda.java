import java.util.List;

class ClientInStreamLambda {
    private final CatalogClient catalog;

    ClientInStreamLambda(CatalogClient catalog) {
        this.catalog = catalog;
    }

    List<String> names(List<String> ids) {
        return ids.stream().map(id -> catalog.find(id)).toList();
    }
}
