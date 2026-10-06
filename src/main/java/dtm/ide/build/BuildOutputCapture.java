package dtm.ide.build;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class BuildOutputCapture extends FilterOutputStream {
    private final BuildDiagnosticParser parser;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean truncated;

    public BuildOutputCapture(OutputStream output, Path root) {
        super(output);
        parser = new BuildDiagnosticParser(root);
    }

    @Override
    public synchronized void write(int value) throws IOException {
        out.write(value);
        capture(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        out.write(bytes, offset, length);
        for (int index = offset; index < offset + length; index++) capture(bytes[index] & 255);
    }

    private void capture(int value) {
        if (value == '\n') finishLine();
        else if (line.size() < 1024 * 1024) line.write(value);
        else truncated = true;
    }

    private void finishLine() {
        if (!truncated) parser.accept(line.toString(StandardCharsets.UTF_8));
        line.reset();
        truncated = false;
    }

    public synchronized BuildResult finish(int exitCode) {
        finishLine();
        return new BuildResult(exitCode, parser.diagnostics());
    }
}
