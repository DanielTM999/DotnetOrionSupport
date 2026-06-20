package dtm.ide.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum DotnetTemplate {

    CONSOLE("dotnet:console", "Console App", "Aplicativo de console multiplataforma", Channel.MODERN, false),
    CLASS_LIBRARY("dotnet:classlib", "Class Library", "Biblioteca de classes .NET", Channel.MODERN, false),
    WEB_EMPTY("dotnet:web-empty", "ASP.NET Core Empty", "Projeto web ASP.NET Core mínimo", Channel.MODERN, false),
    WEB_API("dotnet:web-api", "ASP.NET Core Web API", "API HTTP com endpoints mínimos", Channel.MODERN, false),
    WEB_API_CONTROLLERS("dotnet:web-api-controllers", "ASP.NET Core Web API (Controllers + Swagger)", "API HTTP com controllers separados e Swagger/OpenAPI", Channel.MODERN, false),
    WEB_MVC("dotnet:web-mvc", "ASP.NET Core MVC", "Aplicação web com controllers e views", Channel.MODERN, false),
    BLAZOR("dotnet:blazor", "Blazor Web App", "Aplicação web com componentes Razor", Channel.MODERN, false),
    WORKER("dotnet:worker", "Worker Service", "Serviço em segundo plano", Channel.MODERN, false),
    XUNIT("dotnet:xunit", "xUnit Test Project", "Projeto de testes xUnit", Channel.MODERN, false),
    WPF("dotnet:wpf", "WPF App", "Aplicação desktop WPF (somente Windows)", Channel.MODERN, true),
    WINFORMS("dotnet:winforms", "Windows Forms App", "Aplicação desktop Windows Forms (somente Windows)", Channel.MODERN, true),
    FRAMEWORK_CONSOLE("dotnet:netfx-console", "Console App (.NET Framework)", "Console clássico .NET Framework (somente Windows)", Channel.FRAMEWORK, true),
    FRAMEWORK_CLASSLIB("dotnet:netfx-classlib", "Class Library (.NET Framework)", "Biblioteca clássica .NET Framework (somente Windows)", Channel.FRAMEWORK, true);

    enum Channel {
        MODERN,
        FRAMEWORK
    }

    private static final List<String> MODERN_FRAMEWORKS = List.of("net10.0", "net9.0", "net8.0", "net7.0", "net6.0");
    private static final List<String> NETFX_FRAMEWORKS = List.of("net48", "net472", "net462");
    private static final String DEFAULT_MODERN = "net8.0";
    private static final String DEFAULT_NETFX = "net48";

    private final String id;
    private final String displayName;
    private final String description;
    private final Channel channel;
    private final boolean windowsOnly;

    DotnetTemplate(String id, String displayName, String description, Channel channel, boolean windowsOnly) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.channel = channel;
        this.windowsOnly = windowsOnly;
    }

    String id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    String description() {
        return description;
    }

    Channel channel() {
        return channel;
    }

    boolean windowsOnly() {
        return windowsOnly;
    }

    List<String> frameworkOptions() {
        return channel == Channel.FRAMEWORK ? NETFX_FRAMEWORKS : MODERN_FRAMEWORKS;
    }

    String defaultFramework() {
        return channel == Channel.FRAMEWORK ? DEFAULT_NETFX : DEFAULT_MODERN;
    }

    static List<DotnetTemplate> available() {
        boolean windows = isWindows();
        List<DotnetTemplate> result = new ArrayList<>();
        for (DotnetTemplate template : values()) {
            if (template.windowsOnly && !windows) {
                continue;
            }
            result.add(template);
        }
        return result;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
