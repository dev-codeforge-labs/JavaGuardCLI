import java.io.ObjectInputStream;

class ObjectInputStreamWithFilter {
    Object read(ObjectInputStream in) throws Exception {
        in.setObjectInputFilter(f -> java.io.ObjectInputFilter.Status.ALLOWED);
        return in.readObject();
    }
}
