import org.springframework.web.cors.CorsConfiguration;

class WildcardOriginWithCredentials {
    CorsConfiguration configure() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("*");
        config.setAllowCredentials(true);
        return config;
    }
}
