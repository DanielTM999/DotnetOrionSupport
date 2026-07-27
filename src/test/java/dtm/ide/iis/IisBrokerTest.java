package dtm.ide.iis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisBrokerTest {

    private Process startBroker(Path directory) throws Exception {
        Path script = directory.resolve("broker.ps1");
        Files.writeString(script, IisBroker.brokerScript(directory), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString())
                .redirectErrorStream(true)
                .start();
        Path heartbeat = directory.resolve(IisBroker.HEARTBEAT_FILE);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !Files.isRegularFile(heartbeat)) {
            Thread.sleep(100);
        }
        assertTrue(Files.isRegularFile(directory.resolve("pid.txt")), "o assistente deve publicar o PID ao iniciar");
        assertTrue(Files.isRegularFile(heartbeat), "o assistente deve publicar o heartbeat ao iniciar");
        return process;
    }

    private void stopBroker(Path directory, Process process) throws Exception {
        Files.writeString(directory.resolve("stop"), "1", StandardCharsets.UTF_8);
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void brokerExecutesRequestsAndReturnsOutputAndExitCode(@TempDir Path directory) throws Exception {
        Process broker = startBroker(directory);
        try {
            IisProcess.Result result = IisBroker.submit(directory, IisBroker.Tool.ICACLS,
                    List.of(directory.toString()), 60, broker::isAlive);

            assertEquals(0, result.exitCode(), "icacls deve retornar sucesso: " + result.output());
            assertTrue(result.output().contains(directory.toString()),
                    "a saída deve conter o caminho consultado: " + result.output());
        } finally {
            stopBroker(directory, broker);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void brokerServesSeveralRequestsWithASingleLaunch(@TempDir Path directory) throws Exception {
        Process broker = startBroker(directory);
        try {
            for (int i = 0; i < 3; i++) {
                IisProcess.Result result = IisBroker.submit(directory, IisBroker.Tool.ICACLS,
                        List.of(directory.toString()), 60, broker::isAlive);
                assertEquals(0, result.exitCode(), "requisição " + i + " deve ser atendida pelo mesmo assistente");
            }
            assertTrue(broker.isAlive(), "o assistente deve continuar vivo entre as requisições");
        } finally {
            stopBroker(directory, broker);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void brokerReportsFailingExitCode(@TempDir Path directory) throws Exception {
        Process broker = startBroker(directory);
        try {
            IisProcess.Result result = IisBroker.submit(directory, IisBroker.Tool.ICACLS,
                    List.of(directory.resolve("inexistente-" + System.nanoTime()).toString()), 60, broker::isAlive);

            assertNotEquals(0, result.exitCode(), "caminho inexistente deve falhar");
            assertTrue(!result.output().isBlank(), "a mensagem de erro deve ser capturada");
        } finally {
            stopBroker(directory, broker);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void heartbeatKeepsBrokerDetectedAsAliveBetweenOperations(@TempDir Path directory) throws Exception {
        Process broker = startBroker(directory);
        try {
            assertTrue(IisBroker.heartbeatFresh(directory), "o heartbeat deve estar fresco logo após iniciar");

            for (int i = 0; i < 3; i++) {
                IisBroker.submit(directory, IisBroker.Tool.ICACLS,
                        List.of(directory.toString()), 60, broker::isAlive);
                Thread.sleep(1500);
                assertTrue(IisBroker.heartbeatFresh(directory),
                        "o assistente deve seguir detectado como vivo após a operação " + i);
            }
        } finally {
            stopBroker(directory, broker);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void heartbeatIsAbsentForUnknownDirectory(@TempDir Path directory) {
        assertTrue(!IisBroker.heartbeatFresh(directory.resolve("sem-assistente")),
                "diretório sem assistente não pode ser considerado vivo");
    }

    @Test
    void detectsLiveBrokerProcessWithoutDependingOnHeartbeat() {
        assertTrue(IisBroker.processAlive(ProcessHandle.current().pid()));
        assertTrue(!IisBroker.processAlive(-1));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void brokerStopsWhenSignalled(@TempDir Path directory) throws Exception {
        Process broker = startBroker(directory);
        stopBroker(directory, broker);

        assertTrue(!broker.isAlive(), "o assistente deve encerrar ao receber o sinal de parada");
    }
}
