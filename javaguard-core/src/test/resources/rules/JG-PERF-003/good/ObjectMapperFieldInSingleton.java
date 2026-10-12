import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
class ObjectMapperFieldInSingleton {
    private final ObjectMapper mapper = new ObjectMapper();

    String toJson(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }
}
