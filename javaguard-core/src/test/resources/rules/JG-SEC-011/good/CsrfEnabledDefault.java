import org.springframework.security.config.annotation.web.builders.HttpSecurity;

class CsrfEnabledDefault {
    void configure(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    }
}
