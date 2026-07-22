package dtm.ide.iis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record IisBinding(String protocol, String address, String port, String hostName) {

    public static List<IisBinding> parse(String bindings) {
        List<IisBinding> result = new ArrayList<>();
        if (bindings == null || bindings.isBlank()) {
            return result;
        }
        for (String entry : bindings.split(",")) {
            IisBinding binding = parseSingle(entry.strip());
            if (binding != null) {
                result.add(binding);
            }
        }
        return result;
    }

    public static IisBinding parseSingle(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        int separator = entry.indexOf('/');
        String protocol = separator > 0 ? entry.substring(0, separator) : "http";
        String information = separator > 0 ? entry.substring(separator + 1) : entry;
        String[] parts = information.split(":", -1);
        String address = parts.length > 0 ? parts[0] : "*";
        String port = parts.length > 1 ? parts[1] : defaultPort(protocol);
        String host = parts.length > 2 ? parts[2] : "";
        return new IisBinding(protocol, address, port, host);
    }

    public static String defaultPort(String protocol) {
        return "https".equalsIgnoreCase(protocol) ? "443" : "80";
    }

    public String bindingInformation() {
        return (address == null || address.isBlank() ? "*" : address)
                + ":" + (port == null || port.isBlank() ? defaultPort(protocol) : port)
                + ":" + (hostName == null ? "" : hostName);
    }

    public String descriptor() {
        return (protocol == null || protocol.isBlank() ? "http" : protocol) + "/" + bindingInformation();
    }

    public String url() {
        String scheme = protocol == null || protocol.isBlank() ? "http" : protocol.toLowerCase(Locale.ROOT);
        String host = hostName == null || hostName.isBlank() ? "localhost" : hostName;
        if (address != null && !address.isBlank() && !"*".equals(address) && (hostName == null || hostName.isBlank())) {
            host = address;
        }
        String effectivePort = port == null || port.isBlank() ? defaultPort(scheme) : port;
        if (defaultPort(scheme).equals(effectivePort)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + effectivePort;
    }

    @Override
    public String toString() {
        return descriptor();
    }
}
