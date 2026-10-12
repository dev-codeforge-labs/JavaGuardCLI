class CsrfDisabledChain {
    void configure(Object http) throws Exception {
        ((org.springframework.security.config.annotation.web.builders.HttpSecurity) http).csrf().disable();
    }
}
