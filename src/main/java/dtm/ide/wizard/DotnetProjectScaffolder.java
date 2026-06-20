package dtm.ide.wizard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DotnetProjectScaffolder {

    private static final String CSHARP_PROJECT_TYPE_GUID = "9A19103F-16F7-4668-BE54-9A1E7A4F7556";
    private static final Pattern MAJOR_VERSION = Pattern.compile("net(\\d+)\\.");

    record SourceFile(String path, String content) {
    }

    Path create(DotnetTemplate template, Path rootDir, String name, String framework, boolean separate, boolean git)
            throws Exception {
        Path projectDir = separate ? rootDir.resolve(name) : rootDir;
        Files.createDirectories(projectDir);

        writeString(projectDir.resolve(name + ".csproj"), renderCsproj(template, framework));
        for (SourceFile file : renderSources(template, name)) {
            Path target = projectDir.resolve(file.path());
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeString(target, file.content());
        }

        String csprojRelative = separate ? name + "\\" + name + ".csproj" : name + ".csproj";
        writeString(rootDir.resolve(name + ".sln"), renderSolution(name, csprojRelative));

        if (git) {
            writeString(rootDir.resolve(".gitignore"), defaultGitIgnore());
        }
        return rootDir;
    }

    private static void writeString(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String renderCsproj(DotnetTemplate template, String framework) {
        return switch (template) {
            case CONSOLE -> consoleCsproj(framework);
            case CLASS_LIBRARY -> classLibCsproj(framework);
            case WEB_EMPTY, WEB_API, WEB_MVC, BLAZOR -> webCsproj(framework);
            case WEB_API_CONTROLLERS -> webApiControllersCsproj(framework);
            case WORKER -> workerCsproj(framework);
            case XUNIT -> xunitCsproj(framework);
            case WPF -> wpfCsproj(framework);
            case WINFORMS -> winformsCsproj(framework);
            case FRAMEWORK_CONSOLE -> frameworkConsoleCsproj(framework);
            case FRAMEWORK_CLASSLIB -> frameworkClassLibCsproj(framework);
        };
    }

    private List<SourceFile> renderSources(DotnetTemplate template, String name) {
        String ns = toNamespace(name);
        return switch (template) {
            case CONSOLE -> List.of(new SourceFile("Program.cs",
                    "Console.WriteLine(\"Hello from " + name + "!\");\n"));
            case CLASS_LIBRARY -> List.of(new SourceFile("Class1.cs", classLibSource(ns, name)));
            case WEB_EMPTY -> List.of(
                    new SourceFile("Program.cs", webEmptyProgram()),
                    new SourceFile("appsettings.json", appSettings()));
            case WEB_API -> List.of(
                    new SourceFile("Program.cs", webApiProgram()),
                    new SourceFile("appsettings.json", appSettings()));
            case WEB_API_CONTROLLERS -> List.of(
                    new SourceFile("Program.cs", webApiControllersProgram()),
                    new SourceFile("Controllers/WeatherForecastController.cs", webApiController(ns)),
                    new SourceFile("WeatherForecast.cs", webApiModel(ns)),
                    new SourceFile("Properties/launchSettings.json", apiLaunchSettings()),
                    new SourceFile("appsettings.json", appSettings()),
                    new SourceFile("appsettings.Development.json", appSettingsDevelopment()));
            case WEB_MVC -> List.of(
                    new SourceFile("Program.cs", mvcProgram()),
                    new SourceFile("Controllers/HomeController.cs", mvcController(ns)),
                    new SourceFile("Views/Home/Index.cshtml", mvcIndex(name)),
                    new SourceFile("Views/_ViewImports.cshtml", mvcViewImports(ns)),
                    new SourceFile("appsettings.json", appSettings()));
            case BLAZOR -> List.of(
                    new SourceFile("Program.cs", blazorProgram(ns)),
                    new SourceFile("Components/App.razor", blazorApp()),
                    new SourceFile("Components/Routes.razor", blazorRoutes()),
                    new SourceFile("Components/_Imports.razor", blazorImports(ns)),
                    new SourceFile("Components/Pages/Home.razor", blazorHome(name)),
                    new SourceFile("appsettings.json", appSettings()));
            case WORKER -> List.of(
                    new SourceFile("Program.cs", workerProgram(ns)),
                    new SourceFile("Worker.cs", workerClass(ns)),
                    new SourceFile("appsettings.json", appSettings()));
            case XUNIT -> List.of(new SourceFile("UnitTest1.cs", xunitClass(ns)));
            case WPF -> List.of(
                    new SourceFile("App.xaml", wpfAppXaml(ns)),
                    new SourceFile("App.xaml.cs", wpfAppCode(ns)),
                    new SourceFile("MainWindow.xaml", wpfWindowXaml(ns, name)),
                    new SourceFile("MainWindow.xaml.cs", wpfWindowCode(ns)));
            case WINFORMS -> List.of(
                    new SourceFile("Program.cs", winformsProgram(ns)),
                    new SourceFile("Form1.cs", winformsForm(ns)),
                    new SourceFile("Form1.Designer.cs", winformsDesigner(ns, name)));
            case FRAMEWORK_CONSOLE -> List.of(new SourceFile("Program.cs", frameworkProgram(ns, name)));
            case FRAMEWORK_CLASSLIB -> List.of(new SourceFile("Class1.cs", classLibSource(ns, name)));
        };
    }

    private String consoleCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>Exe</OutputType>
                    <TargetFramework>%TFM%</TargetFramework>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                  </PropertyGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String classLibCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>%TFM%</TargetFramework>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                  </PropertyGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String webCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>%TFM%</TargetFramework>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                  </PropertyGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String workerCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk.Worker">
                  <PropertyGroup>
                    <TargetFramework>%TFM%</TargetFramework>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                  </PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="Microsoft.Extensions.Hosting" Version="%MAJOR%.0.0" />
                  </ItemGroup>
                </Project>
                """.replace("%TFM%", framework).replace("%MAJOR%", majorOf(framework));
    }

    private String xunitCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>%TFM%</TargetFramework>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                    <IsPackable>false</IsPackable>
                    <IsTestProject>true</IsTestProject>
                  </PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.11.1" />
                    <PackageReference Include="xunit" Version="2.9.2" />
                    <PackageReference Include="xunit.runner.visualstudio" Version="2.8.2" />
                  </ItemGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String wpfCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>WinExe</OutputType>
                    <TargetFramework>%TFM%-windows</TargetFramework>
                    <Nullable>enable</Nullable>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <UseWPF>true</UseWPF>
                  </PropertyGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String winformsCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>WinExe</OutputType>
                    <TargetFramework>%TFM%-windows</TargetFramework>
                    <Nullable>enable</Nullable>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <UseWindowsForms>true</UseWindowsForms>
                  </PropertyGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String frameworkConsoleCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <OutputType>Exe</OutputType>
                    <TargetFramework>%TFM%</TargetFramework>
                    <LangVersion>latest</LangVersion>
                  </PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="Microsoft.NETFramework.ReferenceAssemblies" Version="1.0.3">
                      <PrivateAssets>all</PrivateAssets>
                    </PackageReference>
                  </ItemGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String frameworkClassLibCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>%TFM%</TargetFramework>
                    <LangVersion>latest</LangVersion>
                  </PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="Microsoft.NETFramework.ReferenceAssemblies" Version="1.0.3">
                      <PrivateAssets>all</PrivateAssets>
                    </PackageReference>
                  </ItemGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String classLibSource(String ns, String name) {
        return """
                namespace %NS%
                {
                    public class Class1
                    {
                        public string Greeting() => "Hello from %NAME%!";
                    }
                }
                """.replace("%NS%", ns).replace("%NAME%", name);
    }

    private String webEmptyProgram() {
        return """
                var builder = WebApplication.CreateBuilder(args);

                var app = builder.Build();

                app.MapGet("/", () => "Hello World!");

                app.Run();
                """;
    }

    private String webApiProgram() {
        return """
                var builder = WebApplication.CreateBuilder(args);

                var app = builder.Build();

                app.UseHttpsRedirection();

                var summaries = new[]
                {
                    "Freezing", "Bracing", "Chilly", "Cool", "Mild", "Warm", "Balmy", "Hot", "Sweltering", "Scorching"
                };

                app.MapGet("/weatherforecast", () =>
                {
                    var forecast = Enumerable.Range(1, 5).Select(index =>
                        new WeatherForecast
                        (
                            DateOnly.FromDateTime(DateTime.Now.AddDays(index)),
                            Random.Shared.Next(-20, 55),
                            summaries[Random.Shared.Next(summaries.Length)]
                        ))
                        .ToArray();
                    return forecast;
                })
                .WithName("GetWeatherForecast");

                app.Run();

                record WeatherForecast(DateOnly Date, int TemperatureC, string? Summary)
                {
                    public int TemperatureF => 32 + (int)(TemperatureC / 0.5556);
                }
                """;
    }

    private String webApiControllersCsproj(String framework) {
        return """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup>
                    <TargetFramework>%TFM%</TargetFramework>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                  </PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="Swashbuckle.AspNetCore" Version="6.6.2" />
                  </ItemGroup>
                </Project>
                """.replace("%TFM%", framework);
    }

    private String webApiControllersProgram() {
        return """
                var builder = WebApplication.CreateBuilder(args);

                builder.Services.AddControllers();
                builder.Services.AddEndpointsApiExplorer();
                builder.Services.AddSwaggerGen();

                var app = builder.Build();

                if (app.Environment.IsDevelopment())
                {
                    app.UseSwagger();
                    app.UseSwaggerUI();
                }

                app.UseHttpsRedirection();

                app.UseAuthorization();

                app.MapControllers();

                app.Run();
                """;
    }

    private String webApiController(String ns) {
        return """
                using Microsoft.AspNetCore.Mvc;

                namespace %NS%.Controllers;

                [ApiController]
                [Route("[controller]")]
                public class WeatherForecastController : ControllerBase
                {
                    private static readonly string[] Summaries = new[]
                    {
                        "Freezing", "Bracing", "Chilly", "Cool", "Mild", "Warm", "Balmy", "Hot", "Sweltering", "Scorching"
                    };

                    private readonly ILogger<WeatherForecastController> _logger;

                    public WeatherForecastController(ILogger<WeatherForecastController> logger)
                    {
                        _logger = logger;
                    }

                    [HttpGet(Name = "GetWeatherForecast")]
                    public IEnumerable<WeatherForecast> Get()
                    {
                        return Enumerable.Range(1, 5).Select(index => new WeatherForecast
                        {
                            Date = DateOnly.FromDateTime(DateTime.Now.AddDays(index)),
                            TemperatureC = Random.Shared.Next(-20, 55),
                            Summary = Summaries[Random.Shared.Next(Summaries.Length)]
                        })
                        .ToArray();
                    }
                }
                """.replace("%NS%", ns);
    }

    private String webApiModel(String ns) {
        return """
                namespace %NS%;

                public class WeatherForecast
                {
                    public DateOnly Date { get; set; }

                    public int TemperatureC { get; set; }

                    public int TemperatureF => 32 + (int)(TemperatureC / 0.5556);

                    public string? Summary { get; set; }
                }
                """.replace("%NS%", ns);
    }

    private String apiLaunchSettings() {
        return """
                {
                  "$schema": "https://json.schemastore.org/launchsettings.json",
                  "profiles": {
                    "http": {
                      "commandName": "Project",
                      "dotnetRunMessages": true,
                      "launchBrowser": true,
                      "launchUrl": "swagger",
                      "applicationUrl": "http://localhost:5000",
                      "environmentVariables": {
                        "ASPNETCORE_ENVIRONMENT": "Development"
                      }
                    },
                    "https": {
                      "commandName": "Project",
                      "dotnetRunMessages": true,
                      "launchBrowser": true,
                      "launchUrl": "swagger",
                      "applicationUrl": "https://localhost:5001;http://localhost:5000",
                      "environmentVariables": {
                        "ASPNETCORE_ENVIRONMENT": "Development"
                      }
                    }
                  }
                }
                """;
    }

    private String appSettingsDevelopment() {
        return """
                {
                  "Logging": {
                    "LogLevel": {
                      "Default": "Information",
                      "Microsoft.AspNetCore": "Warning"
                    }
                  }
                }
                """;
    }

    private String mvcProgram() {
        return """
                var builder = WebApplication.CreateBuilder(args);

                builder.Services.AddControllersWithViews();

                var app = builder.Build();

                if (!app.Environment.IsDevelopment())
                {
                    app.UseExceptionHandler("/Home/Error");
                    app.UseHsts();
                }

                app.UseHttpsRedirection();
                app.UseRouting();
                app.UseAuthorization();

                app.MapControllerRoute(
                    name: "default",
                    pattern: "{controller=Home}/{action=Index}/{id?}");

                app.Run();
                """;
    }

    private String mvcController(String ns) {
        return """
                using Microsoft.AspNetCore.Mvc;

                namespace %NS%.Controllers;

                public class HomeController : Controller
                {
                    public IActionResult Index()
                    {
                        return View();
                    }
                }
                """.replace("%NS%", ns);
    }

    private String mvcIndex(String name) {
        return """
                @{
                    Layout = null;
                }
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>%NAME%</title>
                </head>
                <body>
                    <h1>Hello from %NAME%!</h1>
                </body>
                </html>
                """.replace("%NAME%", name);
    }

    private String mvcViewImports(String ns) {
        return """
                @using %NS%
                @addTagHelper *, Microsoft.AspNetCore.Mvc.TagHelpers
                """.replace("%NS%", ns);
    }

    private String blazorProgram(String ns) {
        return """
                using %NS%.Components;

                var builder = WebApplication.CreateBuilder(args);

                builder.Services.AddRazorComponents();

                var app = builder.Build();

                if (!app.Environment.IsDevelopment())
                {
                    app.UseExceptionHandler("/Error");
                    app.UseHsts();
                }

                app.UseHttpsRedirection();
                app.UseAntiforgery();

                app.MapRazorComponents<App>();

                app.Run();
                """.replace("%NS%", ns);
    }

    private String blazorApp() {
        return """
                <!DOCTYPE html>
                <html lang="en">

                <head>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <base href="/" />
                    <HeadOutlet />
                </head>

                <body>
                    <Routes />
                </body>

                </html>
                """;
    }

    private String blazorRoutes() {
        return """
                <Router AppAssembly="typeof(Program).Assembly">
                    <Found Context="routeData">
                        <RouteView RouteData="routeData" />
                    </Found>
                </Router>
                """;
    }

    private String blazorImports(String ns) {
        return """
                @using System.Net.Http
                @using Microsoft.AspNetCore.Components
                @using Microsoft.AspNetCore.Components.Forms
                @using Microsoft.AspNetCore.Components.Routing
                @using Microsoft.AspNetCore.Components.Web
                @using %NS%
                @using %NS%.Components
                """.replace("%NS%", ns);
    }

    private String blazorHome(String name) {
        return """
                @page "/"

                <PageTitle>%NAME%</PageTitle>

                <h1>Hello from %NAME%!</h1>
                """.replace("%NAME%", name);
    }

    private String workerProgram(String ns) {
        return """
                using %NS%;

                var builder = Host.CreateApplicationBuilder(args);
                builder.Services.AddHostedService<Worker>();

                var host = builder.Build();
                host.Run();
                """.replace("%NS%", ns);
    }

    private String workerClass(String ns) {
        return """
                namespace %NS%;

                public class Worker : BackgroundService
                {
                    private readonly ILogger<Worker> _logger;

                    public Worker(ILogger<Worker> logger)
                    {
                        _logger = logger;
                    }

                    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
                    {
                        while (!stoppingToken.IsCancellationRequested)
                        {
                            _logger.LogInformation("Worker running at: {time}", DateTimeOffset.Now);
                            await Task.Delay(1000, stoppingToken);
                        }
                    }
                }
                """.replace("%NS%", ns);
    }

    private String xunitClass(String ns) {
        return """
                namespace %NS%;

                public class UnitTest1
                {
                    [Fact]
                    public void Test1()
                    {
                        Assert.True(true);
                    }
                }
                """.replace("%NS%", ns);
    }

    private String wpfAppXaml(String ns) {
        return """
                <Application x:Class="%NS%.App"
                             xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                             xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
                             StartupUri="MainWindow.xaml">
                    <Application.Resources>
                    </Application.Resources>
                </Application>
                """.replace("%NS%", ns);
    }

    private String wpfAppCode(String ns) {
        return """
                using System.Windows;

                namespace %NS%
                {
                    public partial class App : Application
                    {
                    }
                }
                """.replace("%NS%", ns);
    }

    private String wpfWindowXaml(String ns, String name) {
        return """
                <Window x:Class="%NS%.MainWindow"
                        xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
                        Title="%NAME%" Height="450" Width="800">
                    <Grid>
                        <TextBlock Text="Hello from %NAME%!" HorizontalAlignment="Center" VerticalAlignment="Center" FontSize="24" />
                    </Grid>
                </Window>
                """.replace("%NS%", ns).replace("%NAME%", name);
    }

    private String wpfWindowCode(String ns) {
        return """
                using System.Windows;

                namespace %NS%
                {
                    public partial class MainWindow : Window
                    {
                        public MainWindow()
                        {
                            InitializeComponent();
                        }
                    }
                }
                """.replace("%NS%", ns);
    }

    private String winformsProgram(String ns) {
        return """
                namespace %NS%
                {
                    internal static class Program
                    {
                        [STAThread]
                        static void Main()
                        {
                            ApplicationConfiguration.Initialize();
                            Application.Run(new Form1());
                        }
                    }
                }
                """.replace("%NS%", ns);
    }

    private String winformsForm(String ns) {
        return """
                namespace %NS%
                {
                    public partial class Form1 : Form
                    {
                        public Form1()
                        {
                            InitializeComponent();
                        }
                    }
                }
                """.replace("%NS%", ns);
    }

    private String winformsDesigner(String ns, String name) {
        return """
                namespace %NS%
                {
                    partial class Form1
                    {
                        private System.ComponentModel.IContainer components = null;

                        protected override void Dispose(bool disposing)
                        {
                            if (disposing && (components != null))
                            {
                                components.Dispose();
                            }
                            base.Dispose(disposing);
                        }

                        private void InitializeComponent()
                        {
                            this.components = new System.ComponentModel.Container();
                            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
                            this.ClientSize = new System.Drawing.Size(800, 450);
                            this.Text = "%NAME%";
                        }
                    }
                }
                """.replace("%NS%", ns).replace("%NAME%", name);
    }

    private String frameworkProgram(String ns, String name) {
        return """
                using System;

                namespace %NS%
                {
                    internal static class Program
                    {
                        private static void Main()
                        {
                            Console.WriteLine("Hello from %NAME% (.NET Framework)!");
                        }
                    }
                }
                """.replace("%NS%", ns).replace("%NAME%", name);
    }

    private String appSettings() {
        return """
                {
                  "Logging": {
                    "LogLevel": {
                      "Default": "Information",
                      "Microsoft.AspNetCore": "Warning"
                    }
                  },
                  "AllowedHosts": "*"
                }
                """;
    }

    private String renderSolution(String name, String csprojRelative) {
        String projectGuid = "{" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT) + "}";
        return ("""
                Microsoft Visual Studio Solution File, Format Version 12.00
                # Visual Studio Version 17
                VisualStudioVersion = 17.0.31903.59
                MinimumVisualStudioVersion = 10.0.40219.1
                Project("{%TYPE%}") = "%NAME%", "%CSPROJ%", "%GUID%"
                EndProject
                Global
                \tGlobalSection(SolutionConfigurationPlatforms) = preSolution
                \t\tDebug|Any CPU = Debug|Any CPU
                \t\tRelease|Any CPU = Release|Any CPU
                \tEndGlobalSection
                \tGlobalSection(ProjectConfigurationPlatforms) = postSolution
                \t\t%GUID%.Debug|Any CPU.ActiveCfg = Debug|Any CPU
                \t\t%GUID%.Debug|Any CPU.Build.0 = Debug|Any CPU
                \t\t%GUID%.Release|Any CPU.ActiveCfg = Release|Any CPU
                \t\t%GUID%.Release|Any CPU.Build.0 = Release|Any CPU
                \tEndGlobalSection
                EndGlobal
                """)
                .replace("%TYPE%", CSHARP_PROJECT_TYPE_GUID)
                .replace("%NAME%", name)
                .replace("%CSPROJ%", csprojRelative)
                .replace("%GUID%", projectGuid);
    }

    private String defaultGitIgnore() {
        return """
                # .NET / Orion IDE
                bin/
                obj/
                .vs/
                .orion/
                *.user
                packages/
                """;
    }

    private static String majorOf(String framework) {
        Matcher matcher = MAJOR_VERSION.matcher(framework);
        return matcher.find() ? matcher.group(1) : "8";
    }

    private static String toNamespace(String name) {
        String ns = name == null ? "" : name.replaceAll("[^A-Za-z0-9_]", "_");
        if (ns.isEmpty()) {
            ns = "App";
        }
        if (Character.isDigit(ns.charAt(0))) {
            ns = "_" + ns;
        }
        return ns;
    }
}
