import com.fasterxml.jackson.databind.ObjectMapper;

class JacksonNoDefaultTyping {
    void configure(ObjectMapper mapper) {
        mapper.findAndRegisterModules();
    }
}
