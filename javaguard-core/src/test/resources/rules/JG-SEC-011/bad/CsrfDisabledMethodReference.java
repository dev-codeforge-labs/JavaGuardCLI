import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

class CsrfDisabledMethodReference {
    void configure(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
    }
}
