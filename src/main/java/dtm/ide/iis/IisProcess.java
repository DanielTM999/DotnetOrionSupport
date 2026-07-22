package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class IisProcess {

    private static final List<String> DENIED_MARKERS = List.of(
            "access is denied",
            "acesso negado",
            "acesso foi negado",
            "elevated",
            "administrator",
            "administrador",
            "insufficient permission",
            "insufficient privilege",
            "permiss",
            "privilegi",
            "berechtigung",
            "redirection.config",
            "cannot read configuration file",
            "unable to read the configuration");

    private static final Pattern CODE_PAGE = Pattern.compile("(\\d{3,5})\\s*$", Pattern.MULTILINE);

    private static volatile Charset consoleCharset;

    public record Result(int exitCode, String output) {

        public boolean ok() {
            return exitCode == 0;
        }

        public boolean accessDenied() {
            if (output == null) {
                return false;
            }
            String lower = output.toLowerCase(java.util.Locale.ROOT);
            for (String marker : DENIED_MARKERS) {
                if (lower.contains(marker)) {
                    return true;
                }
            }
            return false;
        }
    }

    private IisProcess() {
    }

    public static Result capture(List<String> command, long timeoutSeconds) {
        return capture(command, null, timeoutSeconds);
    }

    public static Result capture(List<String> command, Path workingDirectory, long timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            builder.redirectErrorStream(true);
            process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Process started = process;
            Thread reader = new Thread(() -> pumpQuietly(started.getInputStream(), buffer), "iis-cmd-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return new Result(-1, decode(buffer.toByteArray()));
            }
            reader.join(2000);
            return new Result(process.exitValue(), decode(buffer.toByteArray()));
        } catch (IOException e) {
            return new Result(-1, e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static String decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        try {
            return stripBom(StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString());
        } catch (CharacterCodingException e) {
            return stripBom(new String(raw, consoleCharset()));
        }
    }

    public static String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        int start = 0;
        while (start < value.length() && value.charAt(start) == '﻿') {
            start++;
        }
        return start == 0 ? value : value.substring(start);
    }

    static Charset consoleCharset() {
        Charset cached = consoleCharset;
        if (cached == null) {
            synchronized (IisProcess.class) {
                cached = consoleCharset;
                if (cached == null) {
                    cached = detectConsoleCharset();
                    consoleCharset = cached;
                }
            }
        }
        return cached;
    }

    private static Charset detectConsoleCharset() {
        Charset oem = charsetOfCodePage(detectCodePage());
        if (oem != null && !StandardCharsets.UTF_8.equals(oem)) {
            return oem;
        }
        String name = System.getProperty("native.encoding");
        if (name != null && !name.isBlank()) {
            try {
                return Charset.forName(name);
            } catch (Exception e) {
                log.debug("Charset nativo desconhecido: {}", name);
            }
        }
        return Charset.defaultCharset();
    }

    private static String detectCodePage() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            Process process = new ProcessBuilder("cmd", "/c", "chcp")
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            pumpQuietly(process.getInputStream(), buffer);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            Matcher matcher = CODE_PAGE.matcher(new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Charset charsetOfCodePage(String codePage) {
        if (codePage == null || codePage.isBlank()) {
            return null;
        }
        if ("65001".equals(codePage.strip())) {
            return StandardCharsets.UTF_8;
        }
        for (String candidate : new String[]{"cp" + codePage, "IBM" + codePage, "windows-" + codePage}) {
            try {
                return Charset.forName(candidate);
            } catch (Exception e) {
                log.debug("Codepage não mapeado: {}", candidate);
            }
        }
        return null;
    }

    private static void pumpQuietly(InputStream in, ByteArrayOutputStream out) {
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = in.read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
        } catch (IOException e) {
            log.debug("Leitura de saída interrompida: {}", e.getMessage());
        }
    }
}
