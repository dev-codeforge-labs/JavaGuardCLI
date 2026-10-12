import java.beans.XMLDecoder;

class XmlDecoderUnsafe {
    Object read(XMLDecoder d) {
        return d.readObject();
    }
}
