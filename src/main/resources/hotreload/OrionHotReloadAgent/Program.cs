using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Emit;
using Microsoft.CodeAnalysis.MSBuild;
using Microsoft.CodeAnalysis.Text;
using System.Collections;
using System.Collections.Immutable;
using System.Reflection;
using System.Reflection.Metadata;
using System.Security.Cryptography;
using System.Text;

if (args.Length < 6)
{
    Console.WriteLine("ERROR\targumentos insuficientes para OrionHotReloadAgent");
    return 2;
}

var agent = new OrionHotReloadAgent(
    projectFile: args[0],
    assemblyFile: args[1],
    outputDir: args[2],
    configuration: args[3],
    targetFramework: args[4],
    assemblyName: args[5]);

try
{
    await agent.InitializeAsync();
    Console.WriteLine("READY\tHot Reload pronto");
    await agent.RunAsync();
    return 0;
}
catch (Exception ex)
{
    Console.WriteLine("ERROR\t" + TextUtil.OneLine(ex.Message));
    return 1;
}

static class TextUtil
{
    public static string OneLine(string? text)
    {
        return string.IsNullOrWhiteSpace(text)
            ? "erro desconhecido"
            : text.Replace("\r", " ").Replace("\n", " ").Replace("\t", " ").Trim();
    }
}

sealed class OrionHotReloadAgent : IDisposable
{
    private static readonly SymbolDisplayFormat SymbolKeyFormat = new(
        globalNamespaceStyle: SymbolDisplayGlobalNamespaceStyle.Omitted,
        typeQualificationStyle: SymbolDisplayTypeQualificationStyle.NameAndContainingTypesAndNamespaces,
        genericsOptions: SymbolDisplayGenericsOptions.IncludeTypeParameters,
        memberOptions: SymbolDisplayMemberOptions.IncludeContainingType | SymbolDisplayMemberOptions.IncludeParameters | SymbolDisplayMemberOptions.IncludeExplicitInterface,
        parameterOptions: SymbolDisplayParameterOptions.IncludeType | SymbolDisplayParameterOptions.IncludeParamsRefOut | SymbolDisplayParameterOptions.IncludeExtensionThis,
        miscellaneousOptions: SymbolDisplayMiscellaneousOptions.EscapeKeywordIdentifiers | SymbolDisplayMiscellaneousOptions.UseSpecialTypes);

    private readonly string projectFile;
    private readonly string assemblyFile;
    private readonly string outputDir;
    private readonly string configuration;
    private readonly string targetFramework;
    private readonly string assemblyName;
    private readonly List<IDisposable> disposables = new();
    private Project? currentProject;
    private Compilation? currentCompilation;
    private EmitBaseline? baseline;
    private ModuleMetadata? moduleMetadata;
    private UnitTestingHotReloadBridge? hotReloadService;
    private string currentFingerprint = "";
    private int generation;

    private static readonly ImmutableArray<string> RuntimeCapabilities = ImmutableArray.Create(
        "Baseline",
        "AddMethodToExistingType",
        "AddStaticFieldToExistingType",
        "AddInstanceFieldToExistingType",
        "NewTypeDefinition",
        "ChangeCustomAttributes",
        "UpdateParameters",
        "GenericAddMethodToExistingType",
        "GenericAddFieldToExistingType",
        "GenericNewTypeDefinition");

    public OrionHotReloadAgent(string projectFile, string assemblyFile, string outputDir, string configuration, string targetFramework, string assemblyName)
    {
        this.projectFile = projectFile;
        this.assemblyFile = assemblyFile;
        this.outputDir = outputDir;
        this.configuration = string.IsNullOrWhiteSpace(configuration) ? "Debug" : configuration;
        this.targetFramework = targetFramework ?? "";
        this.assemblyName = assemblyName ?? "";
    }

    public async Task InitializeAsync()
    {
        Directory.CreateDirectory(outputDir);
        var loaded = await LoadProjectAsync(null, CancellationToken.None);
        currentProject = loaded.Project;
        currentCompilation = loaded.Compilation;
        currentFingerprint = await FingerprintAsync(currentProject, CancellationToken.None);
        hotReloadService = new UnitTestingHotReloadBridge(currentProject.Solution.Workspace.Services);
        await hotReloadService.StartSessionAsync(currentProject.Solution, RuntimeCapabilities, CancellationToken.None);

        moduleMetadata = ModuleMetadata.CreateFromFile(assemblyFile);
        disposables.Add(moduleMetadata);
        baseline = EmitBaseline.CreateInitialBaseline(
            currentCompilation,
            moduleMetadata,
            _ => EditAndContinueMethodDebugInformation.Create(ImmutableArray<byte>.Empty, ImmutableArray<byte>.Empty),
            _ => default,
            true);
    }

    public async Task RunAsync()
    {
        string? line;
        while ((line = await Console.In.ReadLineAsync()) != null)
        {
            line = line.Trim().TrimStart('\uFEFF');
            if (line.Length == 0)
            {
                continue;
            }
            if (line.Equals("EXIT", StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
            if (!line.StartsWith("APPLY", StringComparison.OrdinalIgnoreCase))
            {
                Console.WriteLine("ERROR\tcomando desconhecido: " + TextUtil.OneLine(line));
                continue;
            }

            try
            {
                var overlay = ParseApply(line);
                var result = await ApplyAsync(overlay, CancellationToken.None);
                Console.WriteLine(result);
            }
            catch (Exception ex)
            {
                Console.WriteLine("ERROR\t" + TextUtil.OneLine(ex.Message));
            }
        }
    }

    private async Task<string> ApplyAsync(Dictionary<string, string>? overlay, CancellationToken cancellationToken)
    {
        if (currentProject == null || hotReloadService == null)
        {
            return "ERROR\tHot Reload ainda nao foi inicializado";
        }

        var newProject = await BuildUpdatedProjectAsync(currentProject, overlay, cancellationToken);
        var newCompilation = await newProject.GetCompilationAsync(cancellationToken)
            ?? throw new InvalidOperationException("Nao foi possivel compilar o projeto atualizado para Hot Reload.");
        var newFingerprint = await FingerprintAsync(newProject, cancellationToken);
        if (newFingerprint == currentFingerprint)
        {
            return "NOCHANGES\tsem alteracoes para aplicar";
        }

        var compileErrors = newCompilation.GetDiagnostics(cancellationToken)
            .Where(d => d.Severity == DiagnosticSeverity.Error)
            .Take(8)
            .Select(FormatDiagnostic)
            .ToArray();
        if (compileErrors.Length > 0)
        {
            return "BLOCKED\t" + TextUtil.OneLine(string.Join(" | ", compileErrors));
        }

        var (updates, diagnostics) = await hotReloadService.EmitSolutionUpdateAsync(newProject.Solution, true, cancellationToken);
        var blockingDiagnostics = diagnostics.Where(IsBlockingDiagnostic).ToArray();
        if (blockingDiagnostics.Length > 0)
        {
            return "BLOCKED\t" + TextUtil.OneLine(FormatDiagnostics(blockingDiagnostics));
        }
        if (updates.Count == 0)
        {
            currentProject = newProject;
            currentCompilation = newCompilation;
            currentFingerprint = newFingerprint;
            return "NOCHANGES\tsem alteracoes de codigo emitivel para aplicar";
        }

        var delta = updates[0];
        var basePath = Path.Combine(outputDir, "delta-" + Interlocked.Increment(ref generation).ToString("0000"));
        await File.WriteAllBytesAsync(basePath + ".metadata", delta.MetadataDelta.ToArray(), cancellationToken);
        await File.WriteAllBytesAsync(basePath + ".il", delta.ILDelta.ToArray(), cancellationToken);
        await File.WriteAllBytesAsync(basePath + ".pdb", delta.PdbDelta.ToArray(), cancellationToken);
        await WriteEmptyLineUpdatesAsync(basePath + ".bin", cancellationToken);
        currentProject = newProject;
        currentCompilation = newCompilation;
        currentFingerprint = newFingerprint;
        return "OK\t" + basePath;
    }

    private static async Task<Project> BuildUpdatedProjectAsync(Project current, Dictionary<string, string>? overlay, CancellationToken cancellationToken)
    {
        var solution = current.Solution;
        if (overlay != null)
        {
            foreach (var entry in overlay)
            {
                var full = Path.GetFullPath(entry.Key);
                foreach (var doc in current.Documents.Where(d => string.Equals(Path.GetFullPath(d.FilePath ?? ""), full, StringComparison.OrdinalIgnoreCase)).ToArray())
                {
                    solution = solution.WithDocumentText(doc.Id, SourceText.From(entry.Value, Encoding.UTF8), PreservationMode.PreserveIdentity);
                }
            }
        }
        else
        {
            foreach (var doc in current.Documents.Where(d => d.FilePath != null && File.Exists(d.FilePath)))
            {
                var text = await File.ReadAllTextAsync(doc.FilePath!, cancellationToken);
                solution = solution.WithDocumentText(doc.Id, SourceText.From(text, Encoding.UTF8), PreservationMode.PreserveIdentity);
            }
        }
        return solution.GetProject(current.Id)
            ?? throw new InvalidOperationException("Projeto de Hot Reload saiu da solucao atual.");
    }

    private async Task<ProjectLoad> LoadProjectAsync(Dictionary<string, string>? overlay, CancellationToken cancellationToken)
    {
        var props = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["Configuration"] = configuration
        };
        if (!string.IsNullOrWhiteSpace(targetFramework))
        {
            props["TargetFramework"] = targetFramework;
        }

        var workspace = MSBuildWorkspace.Create(props);
        workspace.WorkspaceFailed += (_, e) => Console.Error.WriteLine("workspace: " + e.Diagnostic.Message);
        var project = await workspace.OpenProjectAsync(projectFile, cancellationToken: cancellationToken);
        if (!string.IsNullOrWhiteSpace(assemblyName))
        {
            project = project.WithAssemblyName(assemblyName);
        }

        if (overlay != null)
        {
            foreach (var entry in overlay)
            {
                var full = Path.GetFullPath(entry.Key);
                foreach (var doc in project.Documents.Where(d => string.Equals(Path.GetFullPath(d.FilePath ?? ""), full, StringComparison.OrdinalIgnoreCase)).ToArray())
                {
                    project = project.Solution.WithDocumentText(doc.Id, SourceText.From(entry.Value, Encoding.UTF8)).GetProject(project.Id)!;
                }
            }
        }

        var compilation = await project.GetCompilationAsync(cancellationToken)
            ?? throw new InvalidOperationException("Nao foi possivel compilar o projeto para Hot Reload.");
        return new ProjectLoad(workspace, project, compilation);
    }

    private static Dictionary<string, string>? ParseApply(string line)
    {
        var parts = line.Split('\t');
        if (parts.Length < 3 || string.IsNullOrWhiteSpace(parts[1]) || string.IsNullOrWhiteSpace(parts[2]))
        {
            return null;
        }
        var path = Encoding.UTF8.GetString(Convert.FromBase64String(parts[1]));
        var text = Encoding.UTF8.GetString(Convert.FromBase64String(parts[2]));
        return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase) { [path] = text };
    }

    private static async Task<EditBuildResult> BuildSemanticEditsAsync(Project oldProject, Compilation oldCompilation, Project newProject, Compilation newCompilation, CancellationToken cancellationToken)
    {
        var oldMembers = await CollectMembersAsync(oldProject, oldCompilation, cancellationToken);
        var newMembers = await CollectMembersAsync(newProject, newCompilation, cancellationToken);
        var edits = ImmutableArray.CreateBuilder<SemanticEdit>();

        foreach (var oldMember in oldMembers.Values)
        {
            if (!newMembers.ContainsKey(oldMember.Key))
            {
                return EditBuildResult.Blocked("Remover membros durante Hot Reload ainda exige reiniciar o processo: " + oldMember.Display);
            }
        }

        foreach (var newMember in newMembers.Values)
        {
            if (!oldMembers.TryGetValue(newMember.Key, out var oldMember))
            {
                if (!CanInsert(newMember.Symbol))
                {
                    return EditBuildResult.Blocked("Alteracao estrutural nao suportada em Hot Reload: " + newMember.Display);
                }
                edits.Add(new SemanticEdit(SemanticEditKind.Insert, null, newMember.Symbol, null, false));
                continue;
            }

            if (oldMember.Text == newMember.Text)
            {
                continue;
            }
            if (!CanUpdate(newMember.Symbol))
            {
                return EditBuildResult.Blocked("Alteracao nao suportada em Hot Reload: " + newMember.Display);
            }
            edits.Add(new SemanticEdit(SemanticEditKind.Update, oldMember.Symbol, newMember.Symbol, null, true));
        }

        return EditBuildResult.Ready(edits.ToImmutable());
    }

    private static bool CanUpdate(ISymbol symbol)
    {
        return symbol is IMethodSymbol or IPropertySymbol or IEventSymbol;
    }

    private static bool CanInsert(ISymbol symbol)
    {
        return symbol is IMethodSymbol or IPropertySymbol or IEventSymbol or INamedTypeSymbol;
    }

    private static async Task<Dictionary<string, MemberSnapshot>> CollectMembersAsync(Project project, Compilation compilation, CancellationToken cancellationToken)
    {
        var result = new Dictionary<string, MemberSnapshot>(StringComparer.Ordinal);
        foreach (var document in project.Documents.Where(d => d.SupportsSyntaxTree))
        {
            var root = await document.GetSyntaxRootAsync(cancellationToken);
            if (root == null)
            {
                continue;
            }
            var model = compilation.GetSemanticModel(root.SyntaxTree);
            foreach (var node in CandidateNodes(root))
            {
                var symbol = model.GetDeclaredSymbol(node, cancellationToken);
                if (symbol == null)
                {
                    continue;
                }
                var key = KeyOf(symbol);
                result[key] = new MemberSnapshot(key, symbol, node.ToFullString(), symbol.ToDisplayString(SymbolKeyFormat));
            }
        }
        return result;
    }

    private static IEnumerable<SyntaxNode> CandidateNodes(SyntaxNode root)
    {
        foreach (var node in root.DescendantNodes())
        {
            switch (node)
            {
                case MethodDeclarationSyntax:
                case ConstructorDeclarationSyntax:
                case DestructorDeclarationSyntax:
                case OperatorDeclarationSyntax:
                case ConversionOperatorDeclarationSyntax:
                case AccessorDeclarationSyntax:
                case LocalFunctionStatementSyntax:
                case PropertyDeclarationSyntax property when property.ExpressionBody != null:
                case IndexerDeclarationSyntax indexer when indexer.ExpressionBody != null:
                case EventDeclarationSyntax:
                    yield return node;
                    break;
            }
        }
    }

    private static string KeyOf(ISymbol symbol)
    {
        return symbol.Kind + ":" + symbol.ToDisplayString(SymbolKeyFormat);
    }

    private static async Task<string> FingerprintAsync(Project project, CancellationToken cancellationToken)
    {
        using var sha = SHA256.Create();
        foreach (var document in project.Documents.OrderBy(d => d.FilePath ?? d.Name, StringComparer.OrdinalIgnoreCase))
        {
            var text = await document.GetTextAsync(cancellationToken);
            var pathBytes = Encoding.UTF8.GetBytes(document.FilePath ?? document.Name);
            sha.TransformBlock(pathBytes, 0, pathBytes.Length, null, 0);
            sha.TransformBlock(new byte[] { 0 }, 0, 1, null, 0);
            var content = Encoding.UTF8.GetBytes(text.ToString());
            sha.TransformBlock(content, 0, content.Length, null, 0);
        }
        sha.TransformFinalBlock(Array.Empty<byte>(), 0, 0);
        return Convert.ToHexString(sha.Hash ?? Array.Empty<byte>());
    }

    private static async Task WriteEmptyLineUpdatesAsync(string path, CancellationToken cancellationToken)
    {
        await using var stream = File.Create(path);
        await stream.WriteAsync(new byte[] { 0, 0, 0, 0 }, cancellationToken);
    }

    private static string FormatDiagnostic(Diagnostic diagnostic)
    {
        var span = diagnostic.Location.GetLineSpan();
        var loc = span.Path;
        if (!string.IsNullOrWhiteSpace(loc))
        {
            loc += "(" + (span.StartLinePosition.Line + 1) + "," + (span.StartLinePosition.Character + 1) + ")";
        }
        return string.IsNullOrWhiteSpace(loc)
            ? diagnostic.ToString()
            : loc + ": " + diagnostic.GetMessage();
    }

    private static string FormatDiagnostics(object diagnostics)
    {
        if (diagnostics is not IEnumerable items)
        {
            return diagnostics.ToString() ?? "Alteracao nao suportada por Hot Reload.";
        }
        return string.Join(" | ", items.Cast<object>().Take(8).Select(d => d.ToString()));
    }

    private static bool IsBlockingDiagnostic(object diagnostic)
    {
        if (diagnostic is Diagnostic roslynDiagnostic)
        {
            return roslynDiagnostic.Severity == DiagnosticSeverity.Error;
        }
        var severity = diagnostic.GetType().GetProperty("Severity")?.GetValue(diagnostic)?.ToString();
        return string.Equals(severity, "Error", StringComparison.OrdinalIgnoreCase);
    }

    public void Dispose()
    {
        try
        {
            hotReloadService?.EndSession();
        }
        catch
        {
        }
        foreach (var disposable in disposables)
        {
            disposable.Dispose();
        }
    }

    private sealed record ProjectLoad(MSBuildWorkspace Workspace, Project Project, Compilation Compilation) : IDisposable
    {
        public void Dispose() => Workspace.Dispose();
    }

    private sealed record MemberSnapshot(string Key, ISymbol Symbol, string Text, string Display);

    private sealed record EditBuildResult(bool Success, ImmutableArray<SemanticEdit> Edits, string Message)
    {
        public static EditBuildResult Ready(ImmutableArray<SemanticEdit> edits) => new(true, edits, "");
        public static EditBuildResult Blocked(string message) => new(false, ImmutableArray<SemanticEdit>.Empty, message);
    }
}

sealed class UnitTestingHotReloadBridge
{
    private readonly object service;
    private readonly Type serviceType;

    public UnitTestingHotReloadBridge(Microsoft.CodeAnalysis.Host.HostWorkspaceServices workspaceServices)
    {
        serviceType = FindType("Microsoft.CodeAnalysis.ExternalAccess.UnitTesting.Api.UnitTestingHotReloadService")
            ?? throw new InvalidOperationException("UnitTestingHotReloadService nao encontrado no SDK.");
        service = serviceType.GetConstructor(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                null, new[] { typeof(Microsoft.CodeAnalysis.Host.HostWorkspaceServices) }, null)
            ?.Invoke(new object[] { workspaceServices })
            ?? throw new InvalidOperationException("Nao foi possivel criar UnitTestingHotReloadService.");
    }

    public async Task StartSessionAsync(Solution solution, ImmutableArray<string> capabilities, CancellationToken cancellationToken)
    {
        object task = serviceType.GetMethod("StartSessionAsync", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)!
            .Invoke(service, new object[] { solution, capabilities, cancellationToken })!;
        await (Task)task;
    }

    public async Task<(List<DeltaUpdate> updates, List<object> diagnostics)> EmitSolutionUpdateAsync(Solution solution, bool commitUpdates, CancellationToken cancellationToken)
    {
        object task = serviceType.GetMethod("EmitSolutionUpdateAsync", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)!
            .Invoke(service, new object[] { solution, commitUpdates, cancellationToken })!;
        await (Task)task;
        object result = task.GetType().GetProperty("Result")!.GetValue(task)!;
        object rawUpdates = result.GetType().GetField("Item1")!.GetValue(result)!;
        object rawDiagnostics = result.GetType().GetField("Item2")!.GetValue(result)!;

        var updates = new List<DeltaUpdate>();
        foreach (object update in (IEnumerable)rawUpdates)
        {
            Type t = update.GetType();
            updates.Add(new DeltaUpdate(
                (ImmutableArray<byte>)t.GetField("MetadataDelta", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)!.GetValue(update)!,
                (ImmutableArray<byte>)t.GetField("ILDelta", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)!.GetValue(update)!,
                (ImmutableArray<byte>)t.GetField("PdbDelta", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)!.GetValue(update)!));
        }

        var diagnostics = new List<object>();
        foreach (object diagnostic in (IEnumerable)rawDiagnostics)
        {
            diagnostics.Add(diagnostic);
        }
        return (updates, diagnostics);
    }

    public void EndSession()
    {
        serviceType.GetMethod("EndSession", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
            ?.Invoke(service, Array.Empty<object>());
    }

    private static Type? FindType(string fullName)
    {
        foreach (var assembly in AppDomain.CurrentDomain.GetAssemblies())
        {
            var type = assembly.GetType(fullName, false);
            if (type != null)
            {
                return type;
            }
        }

        try
        {
            return Assembly.Load("Microsoft.CodeAnalysis.Features").GetType(fullName, false);
        }
        catch
        {
            return null;
        }
    }
}

readonly record struct DeltaUpdate(ImmutableArray<byte> MetadataDelta, ImmutableArray<byte> ILDelta, ImmutableArray<byte> PdbDelta);
