package client;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;

public class LineListener {
    public interface LineHandler {
        void onLine(String line) throws Exception;
    }

    private final BufferedReader in;
    private final LineHandler handler;
    private final Runnable onDisconnect;
    private volatile boolean running = true;
    private Thread thread;

    public LineListener(BufferedReader in, LineHandler handler, Runnable onDisconnect) {
        this.in = in;
        this.handler = handler;
        this.onDisconnect = onDisconnect;
    }

    public void start(String threadName) {
        thread = new Thread(() -> {
            try {
                String line;
                while (running && (line = in.readLine()) != null) {
                    handler.onLine(line);
                }
            } catch (Exception ignored) {
                if (running && onDisconnect != null) onDisconnect.run();
            } finally {
                if (running && onDisconnect != null) onDisconnect.run();
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (in instanceof Closeable) {
            try {
                ((Closeable) in).close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean awaitStop(long millis) {
        if (thread == null) return true;
        try {
            thread.join(millis);
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
