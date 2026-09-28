package client;

import java.io.PrintStream;
import java.util.Scanner;

public class TerminalUI {
    private final TerminalTheme theme;
    private final PrintStream out;
    private final Object lock = new Object();
    private volatile String activePrompt;

    public TerminalUI(TerminalTheme theme, PrintStream out) {
        this.theme = theme;
        this.out = out;
    }

    public TerminalTheme theme() {
        return theme;
    }

    public void showHeader() {
        println(theme.header("CHESS MULTIPLAYER CLIENT " + theme.iconSparkle()));
    }

    public void println(String text) {
        synchronized (lock) {
            out.println(text);
        }
    }

    public void info(String text) {
        printEvent(theme.info(text));
    }

    public void ok(String text) {
        printEvent(theme.ok(text));
    }

    public void warn(String text) {
        printEvent(theme.warn(text));
    }

    public void error(String text) {
        printEvent(theme.error(text));
    }

    public void card(String title, String... lines) {
        String sep = System.lineSeparator();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) body.append(sep);
            body.append(lines[i]);
        }
        println(theme.panel(title, body.toString()));
    }

    public void menu(String title, String... options) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < options.length; i++) {
            if (i > 0) body.append("\n");
            body.append(" ").append(i + 1).append(") ").append(options[i]);
        }
        println(theme.panel(title, body.toString()));
    }

    public String prompt(Scanner scanner, String label) {
        String rendered = theme.promptLabel(label);
        synchronized (lock) {
            activePrompt = rendered;
            out.print(rendered);
            out.flush();
        }
        String line = scanner.nextLine();
        synchronized (lock) {
            activePrompt = null;
        }
        return line;
    }

    public void printBoard(String boardText) {
        printEvent(theme.boardContainer(boardText));
    }

    private void printEvent(String text) {
        synchronized (lock) {
            out.println();
            out.println(text);
            if (activePrompt != null) {
                out.print(activePrompt);
                out.flush();
            }
        }
    }
}
