package eu.wohlben.qits.cli.access.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A terminal that records what was done to it; the test types and resizes. */
public final class FakeTerminal implements LocalTerminal {
    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final PipedOutputStream typing = new PipedOutputStream();
    private final PipedInputStream keys;
    public volatile Size size = new Size(120, 40);
    volatile Runnable resized;

    public FakeTerminal() throws IOException {
        keys = new PipedInputStream(typing);
    }

    public void type(String text) throws IOException {
        typing.write(text.getBytes(StandardCharsets.UTF_8));
        typing.flush();
    }

    public void resize(int cols, int rows) {
        size = new Size(cols, rows);
        resized.run();
    }

    public boolean restored() {
        return calls.equals(List.of("raw", "restore"));
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public void enterRaw() {
        calls.add("raw");
    }

    @Override
    public void restore() {
        calls.add("restore");
    }

    @Override
    public Size size() {
        return size;
    }

    @Override
    public boolean onResize(Runnable resized) {
        this.resized = resized;
        return true;
    }

    @Override
    public InputStream keys() {
        return keys;
    }
}
