import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class RestTemplateWithRequestFactory {
    RestTemplate build() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        RestTemplate template = new RestTemplate();
        template.setRequestFactory(factory);
        return template;
    }

    RestTemplate fromFactory(SimpleClientHttpRequestFactory factory) {
        return new RestTemplate(factory);
    }
}
