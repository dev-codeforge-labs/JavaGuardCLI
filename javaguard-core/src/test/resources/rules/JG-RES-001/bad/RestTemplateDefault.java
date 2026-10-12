import org.springframework.web.client.RestTemplate;

class RestTemplateDefault {
    private final RestTemplate http = new RestTemplate();

    String fetch(String url) {
        return http.getForObject(url, String.class);
    }
}
