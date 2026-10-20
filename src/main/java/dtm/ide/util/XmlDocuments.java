package dtm.ide.util;

import org.w3c.dom.Document;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Path;

/** XML reports and project files are untrusted input; never fetch external resources. */
public final class XmlDocuments {
    private XmlDocuments() {}

    public static Document read(Path path) throws IOException {
        return read(path, false);
    }

    public static Document readCoverage(Path path) throws IOException {
        return read(path, true);
    }

    private static Document read(Path path, boolean coverageDoctype) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", !coverageDoctype);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            var document = builder.parse(path.toFile());
            var doctype = document.getDoctype();
            if (doctype != null && (!coverageDoctype || !"coverage".equals(doctype.getName())
                    || doctype.getInternalSubset() != null && !doctype.getInternalSubset().isBlank()))
                throw new IOException("Unsupported XML document type or entity declarations");
            return document;
        } catch (Exception e) {
            throw new IOException("Cannot read XML: " + path + ": " + e.getMessage(), e);
        }
    }
}
