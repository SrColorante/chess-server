package client;

public class TerminalTheme {
    private static final String RESET = "\u001B[0m";
    private static final String FG_ICE = "\u001B[38;5;123m";
    private static final String FG_CYAN = "\u001B[38;5;51m";
    private static final String FG_VIOLET = "\u001B[38;5;141m";
    private static final String FG_OK = "\u001B[38;5;84m";
    private static final String FG_WARN = "\u001B[38;5;208m";
    private static final String FG_ERR = "\u001B[38;5;203m";
    private static final String FG_DIM = "\u001B[38;5;246m";

    private final boolean color;
    private final boolean unicode;
    private final boolean emoji;
    private final boolean compact;

    public TerminalTheme(boolean color, boolean unicode, boolean emoji, boolean compact) {
        this.color = color;
        this.unicode = unicode;
        this.emoji = emoji;
        this.compact = compact;
    }

    public boolean isCompact() { return compact; }

    public String iconOk() { return unicode ? "✓" : "OK"; }
    public String iconError() { return unicode ? "✗" : "ERR"; }
    public String iconInfo() { return unicode ? "•" : "*"; }
    public String iconTurn() { return unicode ? "▶" : ">"; }
    public String iconSparkle() {
        if (!emoji) return unicode ? "◆" : "*";
        return "💠";
    }

    public String header(String text) {
        String rule = compact ? "=".repeat(40) : "=".repeat(58);
        return paint(FG_ICE, rule + "\n" + center(text) + "\n" + rule);
    }

    public String panel(String title, String body) {
        String top = unicode ? "╭─ " + title + " " : "+- " + title + " ";
        String bottom = unicode ? "╰" : "+";
        return paint(FG_DIM, top) + "\n" + body + "\n" + paint(FG_DIM, bottom);
    }

    public String badge(String label, String value) {
        String left = unicode ? "[" : "[";
        String right = unicode ? "]" : "]";
        return paint(FG_VIOLET, left + label + right) + " " + paint(FG_CYAN, value);
    }

    public String info(String text) {
        return paint(FG_CYAN, iconInfo() + " INFO") + " " + text;
    }

    public String ok(String text) {
        return paint(FG_OK, iconOk() + " OK") + " " + text;
    }

    public String warn(String text) {
        return paint(FG_WARN, "! WARN") + " " + text;
    }

    public String error(String text) {
        return paint(FG_ERR, iconError() + " ERROR") + " " + text;
    }

    public String promptLabel(String text) {
        return paint(FG_VIOLET, iconTurn() + " " + text + " ");
    }

    public String boardContainer(String boardText) {
        String shadowPrefix = color ? "\u001B[38;5;238m" : "";
        String shadowReset = color ? RESET : "";
        String decorated = panel("BOARD", boardText);
        if (!color) return decorated;
        return decorated + "\n" + shadowPrefix + "  ░░░░░░" + shadowReset;
    }

    private String center(String text) {
        int width = compact ? 40 : 58;
        int space = Math.max(0, (width - text.length()) / 2);
        return " ".repeat(space) + text;
    }

    private String paint(String ansi, String text) {
        if (!color) return text;
        return ansi + text + RESET;
    }
}
