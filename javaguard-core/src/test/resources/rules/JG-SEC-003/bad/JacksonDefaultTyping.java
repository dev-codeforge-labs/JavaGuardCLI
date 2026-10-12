import com.fasterxml.jackson.databind.ObjectMapper;

class JacksonDefaultTyping {
    void configure(ObjectMapper mapper) {
        mapper.enableDefaultTyping();
    }
}
