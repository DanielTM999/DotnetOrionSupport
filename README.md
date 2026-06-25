# DotnetOrionSupport

Plugin de suporte a **.NET / C#** para a **Orion IDE**. Ele adiciona reconhecimento
de projetos .NET, edição C#, IntelliSense via OmniSharp, build/run/test com `dotnet`,
depuração com `netcoredbg`, gerenciador NuGet, criação de projetos
por wizard e utilitários de projeto integrados aos menus da IDE.

O objetivo é abrir uma pasta .NET e trabalhar nela sem montar a toolchain manualmente:
quando `dotnet`, OmniSharp ou `netcoredbg` não estão disponíveis no sistema, o plugin
baixa e usa cópias gerenciadas dentro da área de recursos da Orion.

## Visão geral

| Área | O que o plugin entrega |
|---|---|
| Adapter de projeto | Reconhece projetos `.sln`, `.slnx`, `.csproj`, `.vbproj`, `.fsproj` e pastas C# |
| Editor | Realce C#, dobras, comentário de linha, XML em arquivos de projeto e ações de código |
| IntelliSense | OmniSharp para completions, hover, diagnósticos, navegação, rename, formatting e símbolos |
| Execução | Build, Run, Test, execução do arquivo atual e comandos de build no menu |
| Depuração | `netcoredbg` com breakpoints, variáveis, call stack, watches, avaliação e atalhos |
| NuGet | Busca, versões, instalados, updates, install/update/uninstall e gestão de fontes |
| Wizard | Templates .NET modernos, ASP.NET Core, Blazor, Worker, xUnit, WPF, WinForms e .NET Framework |
| Configuração | Painel para `.csproj`, `global.json`, format-on-save, prerelease e configuração padrão |

## Reconhecimento de projetos

O adapter é ativado para uma pasta quando encontra algum destes sinais:

- `.dotnet-project`;
- arquivos `packages.config`, `global.json`, `Directory.Build.props`,
  `Directory.Build.targets`, `nuget.config` ou `NuGet.Config`;
- arquivos de solução/projeto: `.sln`, `.slnx`, `.csproj`, `.vbproj`, `.fsproj`;
- `.orion/manifest.json`;
- maioria de arquivos C# (`.cs`, `.csx`, `.cshtml`, `.razor`) em relação a outras linguagens.

Em sistemas não-Windows, projetos exclusivamente `.NET Framework` são reconhecidos de forma
conservadora: o plugin evita habilitar execução/depuração local quando o target exige runtime
Windows clássico.

## Editor e linguagem

Arquivos C# recebem recursos de edição próprios:

- realce de sintaxe via tokenizer C#;
- comentário de linha com `//`;
- dobras para blocos `{ ... }`, comentários `/* ... */` e regiões `#region/#endregion`;
- associação XML para `.csproj` e `.slnx`;
- rastreamento de editores abertos para sincronizar textos com o LSP;
- atualização de diagnósticos publicados pelo OmniSharp;
- lâmpada de ações de código próxima ao caret quando há quick fixes/refactors disponíveis;
- F2 para renomear símbolo no editor ativo.

Arquivos XML de projeto (`.csproj`, `.vbproj`, `.fsproj`, `.props`, `.targets`, `.config`)
recebem regras de dobra por tags.

## IntelliSense com OmniSharp

O plugin inicia o OmniSharp automaticamente ao abrir o projeto. Antes disso ele:

1. resolve o SDK exigido pelo projeto, incluindo `global.json`;
2. baixa o SDK/OmniSharp se necessário;
3. executa restore automático quando não há `obj/project.assets.json`;
4. inicia o language server e conecta os provedores da Orion.

Recursos integrados:

| Recurso | Descrição |
|---|---|
| Autocomplete | Sugestões do OmniSharp para C# e completions em contexto de debug |
| Hover | Informações de símbolos e tipos |
| Diagnósticos | Erros e avisos publicados pelo LSP no editor |
| Ir para definição | Abre o destino local ou navega para fonte/metadata quando disponível |
| Ir para implementação | Integrado ao fluxo de navegação da IDE |
| Find usages | Usa referências do LSP para localizar usos |
| Document symbols | Estrutura do arquivo para navegação |
| Call hierarchy | Chamadas de entrada e saída quando suportadas |
| Rename | Renomeia símbolo no workspace, aplicando alterações multi-arquivo |
| Formatting | Formatação via OmniSharp, manual ou ao salvar quando habilitado |
| Code actions | Quick fixes, refactors, organize imports e fix all quando enviados pelo LSP |

## Build, Run e Test

O plugin registra configurações estáticas de execução:

- **.NET: Compilar**: executa `dotnet build`;
- **.NET: Executar**: compila e executa o projeto com `dotnet`;
- **.NET: Testar**: executa `dotnet test`;
- **Current File** da Orion: habilitado para arquivo C# ativo com `Main` ou top-level statements.

O run usa `TargetFramework` moderno executável no host (`netcoreapp*` ou `net5.0+` compatível).
Projetos `netstandard` e `.NET Framework` ficam limitados a build/test quando não podem ser
executados localmente com segurança.

O menu **Build** recebe:

- Compilar Debug;
- Compilar Release;
- Publicar Release;
- Recompilar (`dotnet build --no-incremental`);
- Limpar (`dotnet clean`).

No menu de contexto da árvore, em arquivos `.sln`, `.slnx`, `.csproj`, `.vbproj` e `.fsproj`,
há ações de compilar, recompilar e limpar diretamente no alvo selecionado.

## Depuração

A depuração usa **netcoredbg** em modo DAP e fica disponível para projetos .NET modernos
executáveis no host. Quando necessário, o plugin baixa o `netcoredbg` automaticamente.

O painel **Debug** inclui:

- aba **Variáveis** com escopos e expansão de objetos;
- aba **Watch** com expressões observadas;
- aba **Pilha de chamadas**;
- toolbar de continue, pause, step over, step into, step out, restart e stop;
- destaque da linha atual no editor;
- popup de exceção quando o programa para por erro;
- avaliação de expressão sob o cursor;
- popup de valor e árvore de objeto;
- completions em expressões de debug, combinando DAP, LSP e variáveis locais.

Atalhos suportados durante debug:

| Atalho | Ação |
|---|---|
| `F5` | Continuar |
| `Shift + F5` | Parar |
| `Ctrl + Shift + F5` | Reiniciar |
| `F6` | Pausar |
| `F10` | Step over |
| `F11` | Step into |
| `Shift + F11` | Step out |

Breakpoints adicionados/removidos na Orion são enviados para a sessão DAP ativa.

## Gerenciador NuGet

O menu **Window → Gerenciar NuGet** abre um painel visual para pacotes NuGet.

Recursos:

- abas **Browse**, **Installed** e **Updates**;
- busca em `nuget.org` ou fontes configuradas;
- seleção de versão;
- opção de incluir prerelease;
- exibição de metadados básicos, autores, downloads e ícones;
- instalação, atualização e desinstalação;
- aplicação em um projeto específico ou em múltiplos projetos encontrados;
- leitura de pacotes instalados via `dotnet list package`;
- suporte a projetos SDK-style com `PackageReference`;
- suporte em melhor esforço a projetos legados com `packages.config`;
- diálogo **Gerenciar fontes NuGet** para adicionar, editar, remover e restaurar fontes.

O menu de contexto da árvore também permite abrir o NuGet focado em uma solução ou projeto.

## Referências de projeto

Em arquivos `.csproj`, o menu de contexto oferece **Adicionar referência de projeto...**.
O plugin:

- procura outros projetos na raiz aberta;
- exibe uma lista com checkboxes;
- detecta referências já existentes;
- aplica inclusões com `dotnet add <csproj> reference <outro.csproj>`;
- aplica remoções com `dotnet remove <csproj> reference <outro.csproj>`;
- escreve a saída em painel próprio.

## Criação de arquivos C#

Em diretórios da árvore de projeto, o menu **New** ganha **C# Class / Interface...**.
O diálogo cria arquivos `.cs` com namespace derivado da pasta e do projeto.

Tipos suportados:

- Class;
- Interface;
- Record;
- Struct;
- Enum.

Nomes são sanitizados para identificadores C# válidos e interfaces são detectadas
automaticamente quando o nome começa com `I` seguido de letra maiúscula.

## Configuração do projeto

O menu **File → Configuração do Projeto (.NET)** abre um painel para editar propriedades
do `.csproj` e `global.json`.

Campos:

- `TargetFramework`;
- `OutputType`;
- `LangVersion`;
- `Nullable`;
- `ImplicitUsings`;
- versão do SDK em `global.json`.

Valores vazios removem a propriedade correspondente do `.csproj`. A alteração é escrita
diretamente no arquivo do projeto e deve ser seguida de restore/rebuild quando necessário.

## Configurações do plugin

Em **Settings → .NET / C#**:

- **Formatar ao salvar (OmniSharp)**: aplica formatação antes de salvar arquivos C#;
- **Incluir versões prerelease no NuGet por padrão**;
- **Configuração padrão de build**: `Debug` ou `Release`.

As configurações são gravadas em `dotnet-settings.properties` na área de recursos do plugin.

## Wizards de projeto

O plugin registra um provider de wizards C# com templates .NET.

Templates modernos:

- Console App;
- Class Library;
- ASP.NET Core Empty;
- ASP.NET Core Web API;
- ASP.NET Core Web API (Controllers + Swagger);
- ASP.NET Core MVC;
- Blazor Web App;
- Worker Service;
- xUnit Test Project;
- WPF App (Windows);
- Windows Forms App (Windows).

Templates .NET Framework:

- Console App (.NET Framework);
- Class Library (.NET Framework).

Frameworks disponíveis:

- modernos: `net10.0`, `net9.0`, `net8.0`, `net7.0`, `net6.0`;
- .NET Framework: `net48`, `net472`, `net462`.

Templates Windows-only são ocultados fora do Windows.

## Toolchain gerenciada

Componentes resolvidos ou baixados sob demanda:

| Componente | Versão padrão | Uso |
|---|---|---|
| .NET SDK | `8.0.422` | build, run, test, restore e templates |
| .NET SDK 10 | `10.0.301` | projetos `net10.0` |
| .NET SDK 9 | `9.0.315` | projetos `net9.0` |
| .NET SDK 7 | `7.0.410` | projetos `net7.0` |
| .NET SDK 6 | `6.0.428` | projetos `net6.0` |
| OmniSharp | `1.39.11` | IntelliSense C# |
| netcoredbg | `3.1.3-1062` | depuração DAP |

O plugin primeiro tenta usar ferramentas instaladas no sistema. Se a versão necessária do SDK
não existir, baixa para `<recursos do plugin>/sdk/...`.

## Organização da árvore

Para reduzir ruído em projetos .NET:

- `bin`, `obj` e `.vs` são ignorados/ocultados na raiz;
- mudanças dentro desses diretórios não forçam reorganização parcial da árvore;
- arquivos C# e arquivos de projeto continuam acessíveis normalmente.

## Arquivos suportados

| Categoria | Arquivos/extensões |
|---|---|
| C# | `.cs`, `.csx`, `.cshtml`, `.razor` |
| Solução/projeto | `.sln`, `.slnx`, `.csproj`, `.vbproj`, `.fsproj` |
| Build MSBuild | `.props`, `.targets` |
| NuGet/config | `packages.config`, `nuget.config`, `NuGet.Config` |
| SDK | `global.json` |

## Build do plugin

```bash
mvn -f pom.xml package
```

O `maven-shade-plugin` gera o JAR final e o `maven-antrun-plugin` copia o artefato para:

```text
%AppData%\Roaming\Orion\plugins
```

Para validação rápida:

```bash
mvn -q test
```

## Licença e componentes

O plugin é distribuído sob licença **MIT**. Componentes externos usados pelo suporte .NET
são livres para uso comercial. O plugin não usa C# Dev Kit nem `vsdbg`; IntelliSense é feito
com OmniSharp e depuração com netcoredbg.

## Limitações conhecidas

- VB.NET e F# podem ser reconhecidos como arquivos de projeto, mas a experiência de edição
  rica é focada em C#.
- Projetos .NET Framework são tratados com cuidado fora do Windows: build/test podem funcionar,
  mas execução/depuração local exigem runtime/debugger compatíveis.
- Inserção de referências e pacotes em projetos legados é feita em melhor esforço; revise o
  `.csproj` quando trabalhar com formatos antigos.

