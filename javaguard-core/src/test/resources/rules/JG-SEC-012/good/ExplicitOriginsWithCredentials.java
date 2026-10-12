import org.springframework.web.cors.CorsConfiguration;

class ExplicitOriginsWithCredentials {
    CorsConfiguration configure() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("https://app.example.com");
        config.setAllowCredentials(true);
        return config;
    }
}
