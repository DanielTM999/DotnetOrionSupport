# DotnetOrionSupport

Plugin de suporte a **.NET / C#** para a **Orion IDE**, no mesmo espírito do
`ClangOrionSupport` (que serviu de referência e **não** foi modificado).

## Recursos

- **Edição C#**: realce de sintaxe, dobras (`{}`, `/* */`, `#region`), comentário
  de linha.
- **IntelliSense** via **OmniSharp** (MIT): autocompletar, hover, ir para
  definição, encontrar referências, símbolos do documento, diagnósticos, rename e
  formatação.
- **Gerenciador de pacotes NuGet** estilo Visual Studio (menu *Window → Gerenciar
  NuGet*): abas **Browse / Installed / Updates**, escolha de **fonte/feed**,
  busca, seleção de versão e ações **Instalar / Atualizar / Desinstalar**.
  - Compatível com **projetos modernos** (`<PackageReference>`, via `dotnet`) e
    **projetos antigos** (`packages.config` / `.csproj` não-SDK).
  - Gestão de feeds via `NuGet.Config` (*Gerenciar fontes…*).
- **Build / Run / Test** via `dotnet` CLI.
- **Desenvolver para Windows/.NET Framework no Linux**: projetos `net4x`
  **compilam** no Linux (com os reference assemblies), mas **não executam** — o
  botão *Executar* é bloqueado com uma mensagem orientando a copiar o binário para
  o Windows. Projetos .NET moderno (`net6.0`+) rodam normalmente.
- **Wizards** (um item por template, estilo Visual Studio): Console App, Class
  Library, ASP.NET Core (Empty / Web API / Web API com Controllers + Swagger /
  MVC), Blazor Web App, Worker Service,
  xUnit Test, WPF App, Windows Forms App e **.NET Framework** (Console / Class
  Library). O framework-alvo é escolhido num seletor (`net10.0`…`net6.0` para os
  modernos; `net48`/`net472`/`net462` para .NET Framework). No Linux os templates
  somente-Windows (WPF, Windows Forms e .NET Framework) ficam ocultos.
- **Configurações**: formatar ao salvar, prerelease por padrão no NuGet,
  configuração padrão de build (Debug/Release).

## Toolchain (detectar e baixar se faltar)

O plugin usa `dotnet`, OmniSharp e netcoredbg já instalados no sistema; se
faltarem, baixa automaticamente para `<recursos do plugin>/sdk/...`. Veja as
versões padrão em `DotnetSdkService`.

## Licença

Plugin sob **MIT** (`LICENSE`). Todos os componentes de terceiros têm licença
livre para uso comercial — veja `THIRD-PARTY-NOTICES.md`. Em particular, **não**
usamos C# Dev Kit nem vsdbg (proprietários): IntelliSense via OmniSharp (MIT) e
depuração via netcoredbg (MIT).

## Build

```bash
mvn -f pom.xml package
```

O `maven-shade-plugin` gera o JAR e o `maven-antrun-plugin` o copia para
`%AppData%\Roaming\Orion\plugins`.

## Limitações conhecidas / próximos passos

- Depuração: o `netcoredbg` é provisionado e *Run* funciona para .NET moderno; a
  sessão DAP completa com breakpoints é um próximo passo (espelhando o
  `ClangDapDebugSession`).
- Linguagens: apenas C# nesta versão (VB.NET/F# ficam para depois).
- A inserção de `<Reference HintPath>` em projetos legados (packages.config) é
  feita em melhor esforço; confira o `.csproj` após instalar.
