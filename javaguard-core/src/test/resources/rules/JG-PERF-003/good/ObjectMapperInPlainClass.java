import com.fasterxml.jackson.databind.ObjectMapper;

class ObjectMapperInPlainClass {
    String toJson(Object value) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(value);
    }
}
