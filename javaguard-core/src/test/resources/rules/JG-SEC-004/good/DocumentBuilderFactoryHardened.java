import javax.xml.parsers.DocumentBuilderFactory;

class DocumentBuilderFactoryHardened {
    void parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.newDocumentBuilder();
    }
}
