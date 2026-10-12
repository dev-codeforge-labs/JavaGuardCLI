import javax.xml.parsers.DocumentBuilderFactory;

class DocumentBuilderFactoryUnhardened {
    void parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.newDocumentBuilder();
    }
}
