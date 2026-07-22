package dtm.ide.iis;

import java.util.List;

public record IisSite(String name,
                      String id,
                      String state,
                      List<IisBinding> bindings,
                      String physicalPath,
                      String applicationPool) {

    public boolean started() {
        return "Started".equalsIgnoreCase(state);
    }

    public String bindingsLabel() {
        if (bindings == null || bindings.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (IisBinding binding : bindings) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(binding.descriptor());
        }
        return builder.toString();
    }

    public String browseUrl() {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        for (IisBinding binding : bindings) {
            if ("http".equalsIgnoreCase(binding.protocol())) {
                return binding.url();
            }
        }
        return bindings.getFirst().url();
    }
}
