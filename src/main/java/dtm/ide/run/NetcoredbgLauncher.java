package dtm.ide.run;

import dtm.ide.iis.IisBroker;
import dtm.ide.iis.IisEnvironment;
import dtm.ide.iis.IisProcess;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Slf4j
final class NetcoredbgLauncher {

    private static final long CONNECT_TIMEOUT_MS = 60_000;
    private static final long CONNECT_RETRY_MS = 250;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 500;
    private static final long BROKER_LAUNCH_TIMEOUT_S = 60;

    private NetcoredbgLauncher() {
    }

    interface DapChannel {

        InputStream input();

        OutputStream output();

        boolean alive();

        String describe();

        void close();
    }

    static DapChannel ofProcess(Process process) {
        return new ProcessChannel(process);
    }

    static DapChannel startServer(Path netcoredbg, Path dotnetRoot, Path cwd) throws IOException {
        if (netcoredbg == null || !Files.isRegularFile(netcoredbg)) {
            throw new IOException("netcoredbg não encontrado para iniciar o servidor de depuração.");
        }
        int port = freePort();
        Path directory = Files.createTempDirectory("orion-dbg-" + UUID.randomUUID());
        Path script = directory.resolve("netcoredbg-server.ps1");
        Path pidFile = directory.resolve("pid.txt");
        Files.writeString(script, serverScript(netcoredbg, dotnetRoot, cwd, port, pidFile), StandardCharsets.UTF_8);

        boolean elevated = IisEnvironment.isElevated();
        Process launcher = elevated ? runDirect(script) : runViaBrokerOrElevate(script);
        Socket socket = connect(port, launcher, directory);
        return new ServerChannel(socket, readPid(pidFile), directory, elevated, port);
    }

    private static Process runViaBrokerOrElevate(Path script) throws IOException {
        if (IisBroker.available()) {
            IisProcess.Result submitted = IisBroker.launchDetachedScript(script, BROKER_LAUNCH_TIMEOUT_S);
            if (submitted.ok()) {
                return null;
            }
            throw new IOException("O assistente elevado do IIS não iniciou o netcoredbg: "
                    + submitted.output());
        }
        return runElevated(script);
    }

    private static Process runDirect(Path script) throws IOException {
        return new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static Process runElevated(Path script) throws IOException {
        String command = "$ErrorActionPreference='Stop'; "
                + "Start-Process -FilePath 'powershell'"
                + " -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File',"
                + quote(script.toString())
                + " -Verb RunAs -WindowStyle Hidden | Out-Null";
        return new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", command)
                .redirectErrorStream(true)
                .start();
    }

    private static Socket connect(int port, Process launcher, Path directory) throws IOException {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        SOCKET_CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException e) {
                last = e;
                closeQuietly(socket);
            }
            if (launcher != null && !launcher.isAlive() && launcher.exitValue() != 0) {
                deleteQuietly(directory);
                throw new IOException("O servidor de depuração elevado não iniciou (a elevação foi cancelada?).");
            }
            sleepQuietly();
        }
        deleteQuietly(directory);
        throw new IOException("netcoredbg não abriu a porta de depuração " + port + " em "
                + (CONNECT_TIMEOUT_MS / 1000) + "s"
                + (last == null ? "." : ": " + last.getMessage()));
    }

    static String serverScript(Path netcoredbg, Path dotnetRoot, Path cwd, int port, Path pidFile) {
        StringBuilder script = new StringBuilder();
        script.append("$ErrorActionPreference = 'Stop'\r\n");
        script.append(privilegeSnippet());
        script.append("[OrionDebugPrivilege]::Enable('SeDebugPrivilege') | Out-Null\r\n");
        if (dotnetRoot != null) {
            String root = dotnetRoot.toString();
            script.append("$env:DOTNET_ROOT = ").append(quote(root)).append("\r\n");
            script.append("$env:DOTNET_ROOT_X64 = ").append(quote(root)).append("\r\n");
        }
        script.append("$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'\r\n");
        script.append("$env:DOTNET_MODIFIABLE_ASSEMBLIES = 'debug'\r\n");
        if (cwd != null) {
            script.append("Set-Location -LiteralPath ").append(quote(cwd.toString())).append("\r\n");
        }
        script.append("$proc = Start-Process -FilePath ")
                .append(quote(netcoredbg.toAbsolutePath().normalize().toString()))
                .append(" -ArgumentList '--interpreter=vscode',")
                .append(quote("--server=" + port))
                .append(" -PassThru -NoNewWindow\r\n");
        script.append("Set-Content -LiteralPath ").append(quote(pidFile.toString()))
                .append(" -Value $proc.Id -Encoding ascii\r\n");
        script.append("$proc.WaitForExit()\r\n");
        script.append("exit $proc.ExitCode\r\n");
        return script.toString();
    }

    private static String privilegeSnippet() {
        return """
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public static class OrionDebugPrivilege {
                    [StructLayout(LayoutKind.Sequential)]
                    struct Luid { public uint Low; public int High; }
                    [StructLayout(LayoutKind.Sequential)]
                    struct TokenPrivileges { public uint Count; public Luid Luid; public uint Attributes; }
                    [DllImport("kernel32.dll")]
                    static extern IntPtr GetCurrentProcess();
                    [DllImport("advapi32.dll", SetLastError = true)]
                    static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
                    [DllImport("advapi32.dll", SetLastError = true)]
                    static extern bool LookupPrivilegeValue(string system, string name, out Luid luid);
                    [DllImport("advapi32.dll", SetLastError = true)]
                    static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll,
                        ref TokenPrivileges state, uint length, IntPtr previous, IntPtr returned);
                    public static bool Enable(string name) {
                        IntPtr token;
                        if (!OpenProcessToken(GetCurrentProcess(), 0x0020 | 0x0008, out token)) return false;
                        Luid luid;
                        if (!LookupPrivilegeValue(null, name, out luid)) return false;
                        TokenPrivileges state = new TokenPrivileges();
                        state.Count = 1;
                        state.Luid = luid;
                        state.Attributes = 0x00000002;
                        if (!AdjustTokenPrivileges(token, false, ref state, 0, IntPtr.Zero, IntPtr.Zero)) return false;
                        return Marshal.GetLastWin32Error() == 0;
                    }
                }
                '@
                """.replace("\n", "\r\n");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static long readPid(Path pidFile) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Files.isRegularFile(pidFile)) {
                    String value = Files.readString(pidFile).strip();
                    if (!value.isBlank()) {
                        return Long.parseLong(value);
                    }
                }
            } catch (Exception e) {
                log.debug("PID do netcoredbg ainda indisponível: {}", e.getMessage());
            }
            sleepQuietly();
        }
        return 0;
    }

    private static String quote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(CONNECT_RETRY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                Files.deleteIfExists(child);
            }
        } catch (Exception e) {
            log.debug("Falha ao limpar temporários do depurador: {}", e.getMessage());
        }
        try {
            Files.deleteIfExists(directory);
        } catch (Exception e) {
            log.debug("Falha ao remover diretório temporário do depurador: {}", e.getMessage());
        }
    }

    private record ProcessChannel(Process process) implements DapChannel {

        @Override
        public InputStream input() {
            return process.getInputStream();
        }

        @Override
        public OutputStream output() {
            return process.getOutputStream();
        }

        @Override
        public boolean alive() {
            return process.isAlive();
        }

        @Override
        public String describe() {
            return "netcoredbg pid=" + process.pid();
        }

        @Override
        public void close() {
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception ignored) {
            }
            process.destroyForcibly();
        }
    }

    private record ServerChannel(Socket socket, long pid, Path directory, boolean elevated, int port)
            implements DapChannel {

        @Override
        public InputStream input() {
            try {
                return socket.getInputStream();
            } catch (IOException e) {
                return InputStream.nullInputStream();
            }
        }

        @Override
        public OutputStream output() {
            try {
                return socket.getOutputStream();
            } catch (IOException e) {
                return OutputStream.nullOutputStream();
            }
        }

        @Override
        public boolean alive() {
            return socket.isConnected() && !socket.isClosed();
        }

        @Override
        public String describe() {
            return "netcoredbg " + (elevated ? "elevado" : "local") + " em 127.0.0.1:" + port
                    + (pid > 0 ? " (pid=" + pid + ")" : "");
        }

        @Override
        public void close() {
            closeQuietly(socket);
            if (pid > 0) {
                ProcessHandle.of(pid).ifPresentOrElse(ProcessHandle::destroyForcibly, this::killElevated);
            }
            deleteQuietly(directory);
        }

        private void killElevated() {
            IisProcess.Result result = IisProcess.capture(
                    List.of("taskkill", "/PID", String.valueOf(pid), "/F"), 10);
            if (!result.ok()) {
                log.debug("Falha ao encerrar netcoredbg elevado pid={}: {}", pid, result.output());
            }
        }
    }
}
