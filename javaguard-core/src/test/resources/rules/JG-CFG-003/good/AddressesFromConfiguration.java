import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

class AddressesFromConfiguration {
    @Value("${inventory.url:http://localhost:9000}")
    String inventory;

    String publicSite = "https://www.example.com/help";
    String embedded = "jdbc:h2:mem:testdb";
    String template = "jdbc:postgresql://%s:%d/%s";
    String message = "Start the server on localhost first";
}

@ConfigurationProperties(prefix = "client")
class ClientProperties {
    private String baseUrl = "http://localhost:8080";
}
