class AnyRequestPermitAll {
    void configure(Object authorize) {
        ((org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer.ExpressionInterceptUrlRegistry) authorize)
                .anyRequest().permitAll();
    }
}
