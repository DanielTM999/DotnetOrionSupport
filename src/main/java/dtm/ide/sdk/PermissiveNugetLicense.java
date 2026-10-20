package dtm.ide.sdk;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipFile;

/** Fail closed before installing downloaded language-server packages. */
public final class PermissiveNugetLicense {
    private static final Set<String> ALLOWED = Set.of("MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD", "CC0-1.0", "Zlib");
    private PermissiveNugetLicense() {}

    public static void verify(Path packageFile) throws IOException {
        try (var zip = new ZipFile(packageFile.toFile())) {
            var manifests = zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".nuspec")
                    && !entry.getName().contains("/")).toList();
            if (manifests.size() != 1) throw new IOException("Expected one package license manifest: " + packageFile);
            byte[] data;
            try (var input = zip.getInputStream(manifests.getFirst())) { data = input.readNBytes(262145); }
            if (data.length > 262144) throw new IOException("Package manifest exceeds size limit");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
            var nodes = document.getElementsByTagNameNS("*", "license");
            if (nodes.getLength() != 1) throw new IOException("Missing package license: " + packageFile);
            var license = (org.w3c.dom.Element) nodes.item(0);
            String expression = license.getTextContent().strip();
            if (!"expression".equals(license.getAttribute("type")) || !ALLOWED.contains(expression))
                throw new IOException("Package license requires review and was not installed: " + expression + " (" + packageFile + ")");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Cannot verify package license: " + packageFile, e); }
    }
}
