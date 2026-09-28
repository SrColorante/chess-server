package client;

import java.util.ArrayList;
import java.util.List;

public class ClientConfig {
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 6700;
    public static final int DEFAULT_TIMEOUT_MS = 30000;

    private final String host;
    private final int port;
    private final int timeoutMs;
    private final boolean colorEnabled;
    private final boolean unicodeEnabled;
    private final boolean emojiEnabled;
    private final boolean animationsEnabled;
    private final boolean compact;

    public ClientConfig(String host,
                        int port,
                        int timeoutMs,
                        boolean colorEnabled,
                        boolean unicodeEnabled,
                        boolean emojiEnabled,
                        boolean animationsEnabled,
                        boolean compact) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.colorEnabled = colorEnabled;
        this.unicodeEnabled = unicodeEnabled;
        this.emojiEnabled = emojiEnabled;
        this.animationsEnabled = animationsEnabled;
        this.compact = compact;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public int getTimeoutMs() { return timeoutMs; }
    public boolean isColorEnabled() { return colorEnabled; }
    public boolean isUnicodeEnabled() { return unicodeEnabled; }
    public boolean isEmojiEnabled() { return emojiEnabled; }
    public boolean isAnimationsEnabled() { return animationsEnabled; }
    public boolean isCompact() { return compact; }

    public static ClientConfig fromArgs(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        int timeout = DEFAULT_TIMEOUT_MS;
        boolean noColor = false;
        boolean noUnicode = false;
        boolean noEmoji = false;
        boolean noAnimations = false;
        boolean forceCompact = false;

        List<String> positionals = new ArrayList<>();
        for (String arg : args) {
            if ("--no-color".equals(arg) || "--no-ansi".equals(arg)) {
                noColor = true;
            } else if ("--no-unicode".equals(arg)) {
                noUnicode = true;
            } else if ("--no-emoji".equals(arg)) {
                noEmoji = true;
            } else if ("--no-animations".equals(arg)) {
                noAnimations = true;
            } else if ("--compact".equals(arg)) {
                forceCompact = true;
            } else if (arg.startsWith("--timeout-ms=")) {
                timeout = parsePortLikeNumber(arg.substring("--timeout-ms=".length()), "timeout");
                if (timeout < 1000 || timeout > 120000) {
                    throw new IllegalArgumentException("Timeout must be between 1000 and 120000 ms");
                }
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                throw new IllegalArgumentException(usage());
            } else {
                positionals.add(arg);
            }
        }

        if (positionals.size() > 0) host = validateHost(positionals.get(0));
        if (positionals.size() > 1) port = validatePort(positionals.get(1));
        if (positionals.size() > 2) {
            throw new IllegalArgumentException("Too many positional arguments.\n" + usage());
        }

        boolean compact = forceCompact || inferCompactTerminal();
        boolean unicodeEnabled = !noUnicode;
        boolean emojiEnabled = !noEmoji && unicodeEnabled;
        return new ClientConfig(host, port, timeout, !noColor, unicodeEnabled, emojiEnabled, !noAnimations, compact);
    }

    public static String validateHost(String host) {
        if (host == null) throw new IllegalArgumentException("Host is required");
        String trimmed = host.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Host cannot be empty");
        if (trimmed.length() > 255) throw new IllegalArgumentException("Host is too long");
        return trimmed;
    }

    public static int validatePort(String portText) {
        int p = parsePortLikeNumber(portText, "port");
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return p;
    }

    private static int parsePortLikeNumber(String value, String fieldName) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + value);
        }
    }

    private static boolean inferCompactTerminal() {
        String cols = System.getenv("COLUMNS");
        if (cols == null) return false;
        try {
            return Integer.parseInt(cols.trim()) < 100;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static String usage() {
        return "Usage: start-client.sh [HOST] [PORT] [--compact] [--no-color] [--no-unicode] [--no-emoji] [--no-animations] [--timeout-ms=30000]";
    }
}
