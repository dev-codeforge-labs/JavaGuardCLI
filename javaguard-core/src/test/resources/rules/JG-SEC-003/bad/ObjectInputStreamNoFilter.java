import java.io.ObjectInputStream;

class ObjectInputStreamNoFilter {
    Object read(ObjectInputStream in) throws Exception {
        return in.readObject();
    }
}
