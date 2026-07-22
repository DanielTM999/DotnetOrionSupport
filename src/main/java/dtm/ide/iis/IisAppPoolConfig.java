package dtm.ide.iis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IisAppPoolConfig {

    public record Property(String category, String name, String label,
                           IisConfig.Kind kind, List<String> options, String description) {

        public static Property text(String category, String name, String label, String description) {
            return new Property(category, name, label, IisConfig.Kind.TEXT, List.of(), description);
        }

        public static Property flag(String category, String name, String label, String description) {
            return new Property(category, name, label, IisConfig.Kind.BOOLEAN, List.of(), description);
        }

        public static Property number(String category, String name, String label, String description) {
            return new Property(category, name, label, IisConfig.Kind.NUMBER, List.of(), description);
        }

        public static Property options(String category, String name, String label, String description,
                                       String... values) {
            return new Property(category, name, label, IisConfig.Kind.ENUM, List.of(values), description);
        }
    }

    public static final String CATEGORY_GENERAL = "(Geral)";
    public static final String CATEGORY_CPU = "CPU";
    public static final String CATEGORY_PROCESS_MODEL = "Modelo de Processo";
    public static final String CATEGORY_ORPHANING = "Órfão de Processo";
    public static final String CATEGORY_RAPID_FAIL = "Proteção de Falha Rápida";
    public static final String CATEGORY_RECYCLING = "Reciclando";

    private IisAppPoolConfig() {
    }

    public static List<Property> properties() {
        List<Property> properties = new ArrayList<>();

        properties.add(Property.flag(CATEGORY_GENERAL, "enable32BitAppOnWin64",
                "Habilitar Aplicativos de 32 Bits",
                "[enable32BitAppOnWin64] Quando verdadeiro, habilita aplicativos de 32 bits em um servidor de 64 bits."));
        properties.add(Property.options(CATEGORY_GENERAL, "startMode", "Modo de Início",
                "[startMode] Especifica se o pool inicia sob demanda ou permanece sempre em execução.",
                "OnDemand", "AlwaysRunning"));
        properties.add(Property.options(CATEGORY_GENERAL, "managedPipelineMode",
                "Modo de Pipeline Gerenciado",
                "[managedPipelineMode] Integrated usa o pipeline unificado; Classic usa o modo do IIS 6.",
                "Integrated", "Classic"));
        properties.add(Property.text(CATEGORY_GENERAL, "name", "Nome",
                "[name] O nome do pool de aplicativos é o identificador exclusivo do pool de aplicativos."));
        properties.add(Property.number(CATEGORY_GENERAL, "queueLength", "Tamanho da Fila",
                "[queueLength] Número máximo de requisições que o HTTP.sys enfileira antes de rejeitar novas."));
        properties.add(Property.options(CATEGORY_GENERAL, "managedRuntimeVersion", "Versão do .NET CLR",
                "[managedRuntimeVersion] Versão do CLR usada pelo pool. Vazio significa \"No Managed Code\".",
                "", "v4.0", "v2.0"));
        properties.add(Property.flag(CATEGORY_GENERAL, "autoStart", "Iniciar Automaticamente",
                "[autoStart] Quando verdadeiro, o pool inicia automaticamente com o serviço."));

        properties.add(Property.options(CATEGORY_CPU, "cpu.action", "Ação Limite",
                "[cpu.action] Ação executada quando o uso de CPU excede o limite configurado.",
                "NoAction", "KillW3wp", "Throttle", "ThrottleUnderLoad"));
        properties.add(Property.flag(CATEGORY_CPU, "cpu.smpAffinitized",
                "Afinidade do Processador Habilitada",
                "[cpu.smpAffinitized] Quando verdadeiro, os processos do pool são afinizados a processadores específicos."));
        properties.add(Property.number(CATEGORY_CPU, "cpu.resetInterval", "Intervalo Limite (minutos)",
                "[cpu.resetInterval] Período, em minutos, para redefinir os contadores de uso de CPU."));
        properties.add(Property.number(CATEGORY_CPU, "cpu.limit", "Limite (por cento)",
                "[cpu.limit] Uso máximo de CPU, em milésimos de por cento, antes de disparar a ação limite."));
        properties.add(Property.number(CATEGORY_CPU, "cpu.smpProcessorAffinityMask",
                "Máscara de Afinidade do Processador",
                "[cpu.smpProcessorAffinityMask] Máscara hexadecimal de processadores em que o pool pode executar."));
        properties.add(Property.number(CATEGORY_CPU, "cpu.smpProcessorAffinityMask2",
                "Máscara de Afinidade do Processador (64 bits)",
                "[cpu.smpProcessorAffinityMask2] Máscara de afinidade para processadores acima de 32 em sistemas de 64 bits."));

        properties.add(Property.options(CATEGORY_PROCESS_MODEL, "processModel.idleTimeoutAction",
                "Ação de tempo limite ocioso",
                "[processModel.idleTimeoutAction] Ação ao atingir o tempo ocioso: encerrar ou suspender o processo.",
                "Terminate", "Suspend"));
        properties.add(Property.flag(CATEGORY_PROCESS_MODEL, "processModel.loadUserProfile",
                "Carregar Perfil do Usuário",
                "[processModel.loadUserProfile] Carrega o perfil do usuário da identidade do pool."));
        properties.add(Property.options(CATEGORY_PROCESS_MODEL, "processModel.identityType", "Identidade",
                "[processModel.identityType] Conta sob a qual o processo de trabalho é executado.",
                "ApplicationPoolIdentity", "LocalService", "LocalSystem", "NetworkService", "SpecificUser"));
        properties.add(Property.text(CATEGORY_PROCESS_MODEL, "processModel.userName",
                "Usuário (identidade específica)",
                "[processModel.userName] Nome de usuário usado quando a identidade é SpecificUser."));
        properties.add(Property.number(CATEGORY_PROCESS_MODEL, "processModel.idleTimeout",
                "Tempo Limite de Ociosidade (minutos)",
                "[processModel.idleTimeout] Tempo que o processo permanece ocioso antes de ser encerrado."));
        properties.add(Property.number(CATEGORY_PROCESS_MODEL, "processModel.maxProcesses",
                "Número Máximo de Processos de Trabalho",
                "[processModel.maxProcesses] Número máximo de processos de trabalho do pool (web garden)."));
        properties.add(Property.flag(CATEGORY_PROCESS_MODEL, "processModel.pingingEnabled",
                "Ping Habilitado",
                "[processModel.pingingEnabled] Verifica periodicamente a saúde do processo de trabalho."));
        properties.add(Property.text(CATEGORY_PROCESS_MODEL, "processModel.pingInterval",
                "Período de Ping (hh:mm:ss)",
                "[processModel.pingInterval] Intervalo entre verificações de integridade do processo."));
        properties.add(Property.text(CATEGORY_PROCESS_MODEL, "processModel.pingResponseTime",
                "Tempo Máximo de Resposta ao Ping (hh:mm:ss)",
                "[processModel.pingResponseTime] Tempo que o processo tem para responder ao ping."));
        properties.add(Property.text(CATEGORY_PROCESS_MODEL, "processModel.shutdownTimeLimit",
                "Limite de Tempo de Desligamento (hh:mm:ss)",
                "[processModel.shutdownTimeLimit] Tempo concedido para o processo encerrar antes de ser terminado."));
        properties.add(Property.text(CATEGORY_PROCESS_MODEL, "processModel.startupTimeLimit",
                "Limite de Tempo de Inicialização (hh:mm:ss)",
                "[processModel.startupTimeLimit] Tempo concedido para o processo iniciar antes de ser terminado."));

        properties.add(Property.text(CATEGORY_ORPHANING, "failure.orphanActionExe", "Executável",
                "[failure.orphanActionExe] Executável acionado quando um processo é órfão."));
        properties.add(Property.flag(CATEGORY_ORPHANING, "failure.orphanWorkerProcess", "Habilitado",
                "[failure.orphanWorkerProcess] Quando verdadeiro, o processo é abandonado em vez de encerrado na falha."));
        properties.add(Property.text(CATEGORY_ORPHANING, "failure.orphanActionParams",
                "Parâmetros do Executável",
                "[failure.orphanActionParams] Parâmetros passados ao executável de órfão."));

        properties.add(Property.text(CATEGORY_RAPID_FAIL, "failure.autoShutdownExe", "Encerrar Executável",
                "[failure.autoShutdownExe] Executável acionado quando o pool é desligado pela proteção de falha rápida."));
        properties.add(Property.text(CATEGORY_RAPID_FAIL, "failure.autoShutdownParams",
                "Encerrar Parâmetros do Executável",
                "[failure.autoShutdownParams] Parâmetros passados ao executável de desligamento."));
        properties.add(Property.flag(CATEGORY_RAPID_FAIL, "failure.rapidFailProtection", "Habilitado",
                "[failure.rapidFailProtection] Desliga o pool após um número de falhas em um intervalo."));
        properties.add(Property.number(CATEGORY_RAPID_FAIL, "failure.rapidFailProtectionInterval",
                "Intervalo de Falha (minutos)",
                "[failure.rapidFailProtectionInterval] Janela de tempo considerada para contar falhas."));
        properties.add(Property.number(CATEGORY_RAPID_FAIL, "failure.rapidFailProtectionMaxCrashes",
                "Máximo de Falhas",
                "[failure.rapidFailProtectionMaxCrashes] Número de falhas no intervalo antes de desligar o pool."));
        properties.add(Property.options(CATEGORY_RAPID_FAIL, "failure.loadBalancerCapabilities",
                "Tipo de Resposta \"Serviço Indisponível\"",
                "[failure.loadBalancerCapabilities] Resposta enviada quando o pool está indisponível.",
                "HttpLevel", "TcpLevel"));

        properties.add(Property.flag(CATEGORY_RECYCLING, "recycling.disallowRotationOnConfigChange",
                "Desabilitar Reciclagem para Alterações de Configuração",
                "[recycling.disallowRotationOnConfigChange] Impede reciclagem automática quando a configuração muda."));
        properties.add(Property.flag(CATEGORY_RECYCLING, "recycling.disallowOverlappingRotation",
                "Desabilitar Reciclagem Sobreposta",
                "[recycling.disallowOverlappingRotation] Impede que o novo processo inicie antes do antigo encerrar."));
        properties.add(Property.number(CATEGORY_RECYCLING, "recycling.periodicRestart.time",
                "Intervalo de Tempo Regular (minutos)",
                "[recycling.periodicRestart.time] Recicla o pool após esse período. Zero desativa."));
        properties.add(Property.number(CATEGORY_RECYCLING, "recycling.periodicRestart.privateMemory",
                "Limite de Memória Privada (KB)",
                "[recycling.periodicRestart.privateMemory] Recicla ao exceder esse consumo de memória privada."));
        properties.add(Property.number(CATEGORY_RECYCLING, "recycling.periodicRestart.memory",
                "Limite de Memória Virtual (KB)",
                "[recycling.periodicRestart.memory] Recicla ao exceder esse consumo de memória virtual."));
        properties.add(Property.number(CATEGORY_RECYCLING, "recycling.periodicRestart.requests",
                "Limite de Solicitação",
                "[recycling.periodicRestart.requests] Recicla após atender esse número de requisições. Zero desativa."));

        return properties;
    }

    public static List<String> categories() {
        List<String> categories = new ArrayList<>();
        for (Property property : properties()) {
            if (!categories.contains(property.category())) {
                categories.add(property.category());
            }
        }
        return categories;
    }

    public static Map<String, String> read(IisAppPool pool) {
        Map<String, String> values = new LinkedHashMap<>();
        if (pool == null) {
            return values;
        }
        Map<String, String> raw = pool.rawAttributes() == null ? Map.of() : pool.rawAttributes();
        for (Property property : properties()) {
            String value = raw.get(property.name());
            values.put(property.name(), value == null ? defaultOf(property, pool) : value);
        }
        return values;
    }

    private static String defaultOf(Property property, IisAppPool pool) {
        return switch (property.name()) {
            case "name" -> pool.name();
            case "managedRuntimeVersion" -> pool.managedRuntimeVersion() == null
                    ? "" : pool.managedRuntimeVersion();
            case "managedPipelineMode" -> pool.managedPipelineMode();
            case "processModel.identityType" -> pool.identityType();
            case "autoStart" -> pool.autoStart();
            case "startMode" -> pool.startMode();
            default -> property.kind() == IisConfig.Kind.BOOLEAN ? "false" : "";
        };
    }

    public static IisService.Result apply(String poolName, Map<String, String> current,
                                          Map<String, String> desired) {
        List<String> arguments = new ArrayList<>(List.of("set", "apppool", poolName));
        boolean changed = false;
        for (Property property : properties()) {
            String previous = current.get(property.name());
            String value = desired.get(property.name());
            if (value == null) {
                continue;
            }
            String normalized = value.strip();
            if (previous != null && previous.strip().equals(normalized)) {
                continue;
            }
            if (previous == null && normalized.isEmpty()) {
                continue;
            }
            if ("name".equals(property.name())) {
                continue;
            }
            arguments.add("/" + property.name() + ":" + normalized);
            changed = true;
        }
        if (!changed) {
            return IisService.Result.ok();
        }
        return IisService.Result.of(AppCmd.write(arguments),
                "Falha ao aplicar configurações avançadas do pool " + poolName);
    }
}
