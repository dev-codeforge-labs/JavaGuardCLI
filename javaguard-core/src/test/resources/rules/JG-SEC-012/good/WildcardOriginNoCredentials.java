import org.springframework.web.cors.CorsConfiguration;

class WildcardOriginNoCredentials {
    CorsConfiguration configure() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("*");
        return config;
    }
}
