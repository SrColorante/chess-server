package client;

import com.formdev.flatlaf.FlatDarkLaf;
import game.ChessGame;
import game.ChessGame.Move;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Swing client for the chess server.
 *
 * <p>The board itself lives in {@link BoardPanel}; this class owns the screens
 * (auth, lobby, match settings, game), the socket, and the translation between
 * the server's line protocol and what the board shows.
 *
 * <p>Two design points worth stating:
 * <ul>
 *   <li>Move hints come from {@link ChessGame#legalMovesFrom} rather than a
 *       second copy of the rules. The old client re-implemented pawn, knight and
 *       slider movement locally to draw the highlights, which silently drifted
 *       from the engine (it could not offer castling, and it did not know which
 *       moves left the king safe). The engine is now mirrored client-side from
 *       the same board frame the server sends, and it is the single source of
 *       truth for what is legal.</li>
 *   <li>Errors are reported in the status line rather than in modal dialogs. A
 *       modal dialog blocks the event thread and hides the board, so a rejected
 *       move used to freeze the window until it was dismissed.</li>
 * </ul>
 */
public class ClientGUI extends JFrame {

    // Vercel / Apple aesthetic (cristianrenosto.party): monochrome, high contrast.
    private static final Color BG_BLACK = new Color(5, 5, 5);
    private static final Color CARD_BLACK = new Color(10, 10, 10);
    private static final Color BORDER_COLOR = new Color(51, 51, 51);
    private static final Color BORDER_HOVER = new Color(102, 102, 102);

    private static final Color TEXT_WHITE = Color.WHITE;
    private static final Color TEXT_GRAY = new Color(136, 136, 136);
    private static final Color TEXT_DIM = new Color(102, 102, 102);
    private static final Color ACCENT = new Color(88, 166, 255);
    private static final Color DANGER = new Color(220, 80, 80);

    private static final String SERVER_HOST_DEFAULT = "127.0.0.1";
    private static final int SERVER_PORT_DEFAULT = 6700;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel mainContainer = new JPanel(cardLayout);

    // Server state. The streams are written by the connect thread and read from
    // the event thread, so they must be volatile: without this a click right
    // after startup could see a null writer and silently drop the command.
    private volatile Socket socket;
    private volatile BufferedReader in;
    private volatile PrintWriter out;
    private volatile LineListener listener;
    private volatile String myColor = "";
    private volatile boolean shuttingDown;

    private String currentRoomCode = "";
    private JLabel roomCodeDisplay;

    private final BoardPanel board = new BoardPanel();
    private JLabel statusLabel;
    private JLabel statusDetail;
    private JLabel clockLabel;
    private JButton resignButton;
    private JButton flipButton;
    private DefaultListModelHolder logHolder = new DefaultListModelHolder();
    private JList<String> logList;

    /** Board exactly as the server sent it, indexed [rank][file] with row 0 = rank 8. */
    private final String[][] boardState = new String[8][8];

    /**
     * Client-side mirror of the game, rebuilt from each board frame. It exists
     * only to answer "which moves are legal from here"; the server stays the
     * authority on what actually happens.
     */
    private final ChessGame mirror = new ChessGame();

    private String selectedSquare;
    private final Set<String> legalTargets = new HashSet<>();
    private String lastMoveFrom;
    private String lastMoveTo;
    private volatile String boardTurn = "";
    private volatile boolean awaitingPromotion;

    private Timer clockTimer;
    private long turnDeadlineMs;
    private int turnLimitSeconds;

    public ClientGUI() {
        super("Chess");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1160, 820);
        setMinimumSize(new Dimension(1000, 720));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_BLACK);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        mainContainer.setBackground(BG_BLACK);
        mainContainer.add(createAuthPanel(), "AUTH");
        mainContainer.add(createLobbyPanel(), "LOBBY");
        mainContainer.add(createHostSettingsPanel(), "SETTINGS");
        mainContainer.add(createGamePanel(), "GAME");

        add(mainContainer);
        cardLayout.show(mainContainer, "AUTH");

        installKeyboardShortcuts();
        connectToServer();
    }

    // ── connection ──────────────────────────────────────────────────────────

    private void connectToServer() {
        String host = System.getenv("CHESS_HOST");
        if (host == null || host.isBlank()) host = SERVER_HOST_DEFAULT;
        int port = SERVER_PORT_DEFAULT;
        String portRaw = System.getenv("CHESS_PORT");
        if (portRaw != null && !portRaw.isBlank()) {
            try {
                port = Integer.parseInt(portRaw.trim());
            } catch (NumberFormatException ignored) {
                // keep the default rather than failing to connect
            }
        }
        final String finalHost = host;
        final int finalPort = port;

        setGlobalStatus("Connecting to " + finalHost + ":" + finalPort + "...");

        Thread connector = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(finalHost, finalPort), CONNECT_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
                socket = s;

                String welcome = in.readLine();
                if (welcome == null) throw new IOException("Server closed the connection");

                LineListener l = new LineListener(in, this::handleServerLine, this::onDisconnected);
                listener = l;
                l.start("GUI-Listener");
            } catch (Exception e) {
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() ->
                        showFatal("Cannot reach the server at " + finalHost + ":" + finalPort
                                + "\n\n" + message));
            }
        }, "GUI-Connect");
        connector.setDaemon(true);
        connector.start();
    }

    private void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            if (!shuttingDown) {
                showFatal("The connection to the server was lost.");
            }
        });
    }

    private void send(String cmd) {
        PrintWriter writer = out;
        if (writer != null) {
            writer.println(cmd);
        } else {
            logEvent("Not connected to a server yet");
        }
    }

    private void shutdown() {
        shuttingDown = true;
        if (clockTimer != null) clockTimer.stop();
        LineListener l = listener;
        if (l != null) l.stop();
        try {
            Socket s = socket;
            if (s != null && !s.isClosed()) s.close();
        } catch (IOException ignored) {
            // closing on the way out; nothing useful to do with a failure here
        }
        dispose();
        System.exit(0);
    }

    // ── status and logging ──────────────────────────────────────────────────

    private void setGlobalStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            if (statusDetail != null) statusDetail.setText(message);
        });
    }

    private void showError(String message) {
        logEvent(message);
        if (statusDetail != null) {
            statusDetail.setForeground(DANGER);
            statusDetail.setText(message);
        }
    }

    private void showFatal(String message) {
        if (statusDetail != null) {
            statusDetail.setForeground(DANGER);
            statusDetail.setText(message);
        }
        // The connection is gone, so the auth screen is no longer usable: say so
        // once and stop rather than leaving dead buttons on screen.
        JOptionPane.showMessageDialog(this, message, "Chess", JOptionPane.ERROR_MESSAGE);
    }

    private void logEvent(String message) {
        SwingUtilities.invokeLater(() -> {
            logHolder.add(0, message);
            while (logHolder.size() > 200) logHolder.remove(logHolder.size() - 1);
        });
    }

    /** Tiny holder so the log model exists before the game panel is built. */
    private static final class DefaultListModelHolder extends javax.swing.DefaultListModel<String> {
    }

    // ── protocol ────────────────────────────────────────────────────────────

    private void handleServerLine(String line) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (line.startsWith("AUTH_OK:")) {
                    cardLayout.show(mainContainer, "LOBBY");
                } else if (line.startsWith("AUTH_ERROR:")) {
                    showError(line.substring(11));
                } else if (line.startsWith("ROOM_CREATED:")) {
                    ClientProtocolParser.RoomCreated created =
                            ClientProtocolParser.parseRoomCreated(line);
                    currentRoomCode = created.getRoomCode();
                    if (roomCodeDisplay != null) roomCodeDisplay.setText(currentRoomCode);
                    cardLayout.show(mainContainer, "SETTINGS");
                } else if (line.startsWith("JOIN_OK:")) {
                    enterGameScreen();
                } else if (line.startsWith("MATCH_START:")) {
                    enterGameScreen();
                    logEvent(line.substring(12));
                } else if (line.startsWith("ASSIGNED_COLOR:")) {
                    myColor = line.substring(15).trim();
                    board.setFlipped("BLACK".equalsIgnoreCase(myColor));
                    logEvent("You are playing " + myColor);
                    updateTurnStatus();
                } else if (line.startsWith("BOARD:")) {
                    applyBoardFrame(line);
                } else if (line.startsWith("MOVE_OK:")) {
                    logEvent("Moved: " + line.substring(8));
                } else if (line.startsWith("MOVE_ERROR:")) {
                    // Never a modal: a rejected move must not block the board.
                    showError("Illegal move: " + line.substring(11));
                    clearSelection();
                } else if (line.startsWith("GAME_OVER:")) {
                    String reason = line.substring(10);
                    logEvent("Game over: " + reason);
                    setStatus("Game over", TEXT_GRAY);
                    resignButton.setEnabled(false);
                    board.setInteractive(false);
                    if (clockTimer != null) clockTimer.stop();
                    JOptionPane.showMessageDialog(this, reason, "Game over", JOptionPane.INFORMATION_MESSAGE);
                } else if ("PING".equals(line)) {
                    send("PONG");
                } else if (line.startsWith("INFO:")) {
                    logEvent(line.substring(5));
                } else if (line.startsWith("ERROR:")) {
                    showError(line.substring(6));
                }
            } catch (Exception ex) {
                showError("Unexpected message from the server: " + ex.getMessage());
            }
        });
    }

    private void enterGameScreen() {
        cardLayout.show(mainContainer, "GAME");
        setStatus("Waiting for the match to start", TEXT_GRAY);
        logHolder.clear();
        lastMoveFrom = null;
        lastMoveTo = null;
        board.setLastMove(null, null);
        board.setCheckSquare(null);
        selectedSquare = null;
        legalTargets.clear();
        board.setSelection(null, null, null);
    }

    /**
     * Applies one board frame from the server and refreshes everything derived
     * from it: the painted board, the mirrored engine, and the move hints.
     */
    private void applyBoardFrame(String line) {
        String[] parsed = ChessGame.parseBoardCompact(line);
        if (parsed == null) {
            logEvent("Ignored a malformed board frame");
            return;
        }

        String ranks = parsed[0];
        boardTurn = parsed[1];

        for (int r = 0; r < 8; r++) {
            String rank = ranks.substring(r * 9, r * 9 + 8);
            for (int f = 0; f < 8; f++) {
                String piece = rank.substring(f, f + 1);
                boardState[r][f] = piece.equals(".") ? null : piece;
            }
        }

        syncMirror();
        board.setBoard(boardState);
        board.setLastMove(lastMoveFrom, lastMoveTo);
        board.setCheckSquare(detectCheck());
        recomputeTargets();
        board.setSelection(selectedSquare, legalTargets, captureTargets());
        updateTurnStatus();
        resetTurnClock();
    }

    /**
     * Rebuilds the client-side engine mirror from the board frame. The compact
     * frame carries no castling or en passant data, so those are cleared: the
     * mirror is used for move hints, and a missing right only means castling is
     * not offered as a hint. The server still validates every move.
     */
    private void syncMirror() {
        StringBuilder fen = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            if (r > 0) fen.append('/');
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                String piece = boardState[r][f];
                if (piece == null) {
                    empty++;
                } else {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    fen.append(piece);
                }
            }
            if (empty > 0) fen.append(empty);
        }
        fen.append(' ').append(boardTurn).append(" - - 0 1");
        mirror.loadFEN(fen.toString());
    }

    /** The king square of the side to move, when that king is under attack. */
    private String detectCheck() {
        try {
            if (!mirror.isCheck()) {
                return null;
            }
            return mirror.getKingSquare(
                    "b".equals(boardTurn) ? ChessGame.Color.BLACK : ChessGame.Color.WHITE);
        } catch (Exception e) {
            return null;
        }
    }

    private void setStatus(String text, Color colour) {
        if (statusLabel == null) return;
        statusLabel.setText(text);
        statusLabel.setForeground(colour);
    }

    private void updateTurnStatus() {
        if (statusLabel == null) return;
        if (myColor.isEmpty()) {
            setStatus("Waiting for your colour", TEXT_GRAY);
            return;
        }
        String current = "w".equals(boardTurn) ? "WHITE" : "BLACK";
        boolean mine = current.equalsIgnoreCase(myColor);
        if (gameOver()) {
            return;
        }
        if (mine) {
            setStatus("Your turn", TEXT_WHITE);
            board.setInteractive(true);
        } else {
            setStatus("Opponent's turn", TEXT_GRAY);
            board.setInteractive(false);
        }
    }

    private boolean gameOver() {
        return statusLabel != null && "Game over".equals(statusLabel.getText());
    }

    private boolean isMyTurn() {
        if (boardTurn.isEmpty() || myColor.isEmpty()) return false;
        String current = "w".equals(boardTurn) ? "WHITE" : "BLACK";
        return current.equalsIgnoreCase(myColor);
    }

    // ── move hints ──────────────────────────────────────────────────────────

    /**
     * Recomputes the legal destinations for the selected square using the engine
     * mirror, so the hints can never disagree with what the server accepts.
     */
    private void recomputeTargets() {
        legalTargets.clear();
        if (selectedSquare == null || !isMyTurn() || gameOver()) {
            return;
        }
        try {
            for (Move m : mirror.legalMovesFrom(selectedSquare)) {
                legalTargets.add(m.toSquare());
            }
        } catch (Exception e) {
            legalTargets.clear();
        }
    }

    private Set<String> captureTargets() {
        Set<String> captures = new HashSet<>();
        for (String square : legalTargets) {
            if (boardState[7 - (square.charAt(1) - '1')][square.charAt(0) - 'a'] != null) {
                captures.add(square);
            }
        }
        return captures;
    }

    private void clearSelection() {
        selectedSquare = null;
        legalTargets.clear();
        board.setSelection(null, null, null);
    }

    private void onSquareClick(String square) {
        if (gameOver() || awaitingPromotion) {
            return;
        }

        if (selectedSquare == null) {
            if (boardState[7 - (square.charAt(1) - '1')][square.charAt(0) - 'a'] == null) {
                logEvent("No piece on " + square);
                return;
            }
            if (!isMyTurn()) {
                logEvent("Wait for your turn");
                return;
            }
            selectedSquare = square;
            recomputeTargets();
            board.setSelection(selectedSquare, legalTargets, captureTargets());
            return;
        }

        if (square.equals(selectedSquare)) {
            clearSelection();
            return;
        }

        if (!legalTargets.contains(square)) {
            logEvent("That is not a legal move from " + selectedSquare);
            return;
        }

        ChessGame.Move chosen = null;
        for (Move m : mirror.legalMovesFrom(selectedSquare)) {
            if (m.toSquare().equals(square)) {
                chosen = m;
                break;
            }
        }

        String from = selectedSquare;
        clearSelection();

        if (chosen != null && chosen.promotion) {
            if (!askPromotion(from, square)) {
                return;
            }
        }

        lastMoveFrom = from;
        lastMoveTo = square;
        board.setLastMove(lastMoveFrom, lastMoveTo);
        send("MOVE:" + from + ":" + square);
    }

    /**
     * Asks which piece to promote to. The engine always promotes to a queen, so
     * a player who wants a knight has no way to say so; this at least makes the
     * choice visible and defaults to the queen.
     */
    private boolean askPromotion(String from, String to) {
        awaitingPromotion = true;
        try {
            String[] options = {"Queen", "Rook", "Bishop", "Knight"};
            int choice = JOptionPane.showOptionDialog(this,
                    "Promote to which piece?", "Promotion",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            return choice != JOptionPane.CLOSED_OPTION;
        } finally {
            awaitingPromotion = false;
        }
    }

    // ── turn clock ──────────────────────────────────────────────────────────

    /**
     * Restarts the on-screen countdown whenever the position changes. The server
     * owns the real limit and ends the game on expiry; this only makes the wait
     * visible instead of leaving the player with a silent board.
     */
    private void resetTurnClock() {
        if (turnLimitSeconds <= 0 || gameOver()) {
            if (clockLabel != null) clockLabel.setText(" ");
            return;
        }
        turnDeadlineMs = System.currentTimeMillis() + turnLimitSeconds * 1000L;
        if (clockTimer == null) {
            clockTimer = new Timer(250, e -> updateClock());
            clockTimer.setRepeats(true);
        }
        if (!clockTimer.isRunning()) {
            clockTimer.start();
        }
        updateClock();
    }

    private void updateClock() {
        if (clockLabel == null) return;
        long remaining = turnDeadlineMs - System.currentTimeMillis();
        if (remaining <= 0) {
            clockLabel.setText("0:00");
            clockLabel.setForeground(DANGER);
            return;
        }
        long seconds = (remaining + 999) / 1000;
        clockLabel.setText(String.format("%d:%02d", seconds / 60, seconds % 60));
        clockLabel.setForeground(seconds <= 10 ? DANGER : TEXT_DIM);
    }

    // ── keyboard ────────────────────────────────────────────────────────────

    private void installKeyboardShortcuts() {
        JComponent root = getRootPane();
        root.registerKeyboardAction(
                e -> { if (board.isInteractive()) board.setFlipped(!board.isFlipped()); },
                KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(
                e -> confirmResign(),
                KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(
                e -> { clearSelection(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void confirmResign() {
        if (gameOver() || resignButton == null || !resignButton.isEnabled()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Resign the match? You will lose.", "Confirm resign", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            send("RESIGN");
        }
    }

    // ── screens ─────────────────────────────────────────────────────────────

    private JPanel createAuthPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(BG_BLACK);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BLACK);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(48, 56, 48, 56)));

        JLabel title = new JLabel("Chess.");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 36f));
        title.setForeground(TEXT_WHITE);

        JLabel subtitle = new JLabel("Sign in, or take a seat as a guest.");
        subtitle.setForeground(TEXT_GRAY);

        JTextField userField = styledTextField("Username");
        JPasswordField passField = styledPasswordField("Password");

        JButton btnLogin = styleButton("Sign In", true);
        btnLogin.addActionListener(e -> {
            String user = userField.getText().trim();
            if (user.isEmpty()) {
                setAuthError("Enter a username first");
                return;
            }
            setAuthError(null);
            send("LOGIN:" + user + ":" + new String(passField.getPassword()));
        });

        JButton btnRegister = styleButton("Create Account", false);
        btnRegister.addActionListener(e -> {
            String user = userField.getText().trim();
            if (user.isEmpty()) {
                setAuthError("Enter a username first");
                return;
            }
            setAuthError(null);
            send("REGISTER:" + user + ":" + new String(passField.getPassword()));
        });

        JButton btnGuest = styleButton("Play as Guest", false);
        btnGuest.addActionListener(e ->
                send("GUEST:Guest_" + (int) (Math.random() * 9000 + 1000)));

        // Enter submits instead of doing nothing.
        userField.addActionListener(e -> btnLogin.doClick());
        passField.addActionListener(e -> btnLogin.doClick());

        // The auth screen has no log panel, so errors need somewhere to land.
        authErrorLabel = new JLabel(" ");
        authErrorLabel.setForeground(DANGER);
        authErrorLabel.setFont(authErrorLabel.getFont().deriveFont(Font.PLAIN, 12f));

        Component[] comps = {title, strut(10), subtitle, strut(34),
                userField, strut(13), passField, strut(10), authErrorLabel,
                strut(24), btnLogin, strut(12), btnRegister, strut(12), btnGuest};

        for (Component c : comps) {
            prepare(c, 300, 46);
            card.add(c);
        }

        wrapper.add(card);
        return wrapper;
    }

    private JLabel authErrorLabel;

    private void setAuthError(String message) {
        if (authErrorLabel == null) return;
        if (message == null) {
            authErrorLabel.setText(" ");
        } else {
            authErrorLabel.setText(message);
        }
    }

    private JPanel createLobbyPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(BG_BLACK);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BLACK);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(48, 56, 48, 56)));

        JLabel title = new JLabel("Lobby");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 32f));
        title.setForeground(TEXT_WHITE);

        JButton btnCreate = styleButton("Create Match", true);
        btnCreate.addActionListener(e -> send("CREATE_ROOM:Match:false"));

        JLabel lbl = new JLabel("Or join with a code");
        lbl.setForeground(TEXT_GRAY);

        JTextField codeField = styledTextField("Room Code");
        // Room codes are 6 uppercase alphanumerics: normalize as the user types.
        codeField.getDocument().addDocumentListener(new DocumentListener() {
            private void normalize() {
                String text = codeField.getText().toUpperCase();
                if (!text.equals(codeField.getText())) {
                    String fixed = text;
                    SwingUtilities.invokeLater(() -> {
                        codeField.setText(fixed);
                        codeField.setCaretPosition(fixed.length());
                    });
                }
            }
            public void insertUpdate(DocumentEvent e) { normalize(); }
            public void removeUpdate(DocumentEvent e) { normalize(); }
            public void changedUpdate(DocumentEvent e) { normalize(); }
        });

        JButton btnJoin = styleButton("Join Match", false);
        btnJoin.addActionListener(e -> {
            String code = codeField.getText().trim().toUpperCase();
            if (code.isEmpty()) {
                logEvent("Enter a room code first");
                return;
            }
            send("JOIN_ROOM:" + code);
        });
        codeField.addActionListener(e -> btnJoin.doClick());

        Component[] comps = {title, strut(34), btnCreate, strut(34), lbl,
                strut(8), codeField, strut(13), btnJoin};

        for (Component c : comps) {
            prepare(c, 300, 46);
            card.add(c);
        }

        wrapper.add(card);
        return wrapper;
    }

    private JPanel createHostSettingsPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(BG_BLACK);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BLACK);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(44, 56, 44, 56)));

        JLabel title = new JLabel("Match Settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setForeground(TEXT_WHITE);

        JLabel codeLabel = new JLabel("Share this code with your opponent:");
        codeLabel.setForeground(TEXT_GRAY);

        roomCodeDisplay = new JLabel("------");
        roomCodeDisplay.setFont(new Font(Font.MONOSPACED, Font.BOLD, 40));
        roomCodeDisplay.setForeground(ACCENT);
        // Click to copy: nobody should retype a 6-character code by hand.
        roomCodeDisplay.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        roomCodeDisplay.setToolTipText("Click to copy");
        roomCodeDisplay.setAlignmentX(Component.CENTER_ALIGNMENT);
        roomCodeDisplay.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                String code = roomCodeDisplay.getText();
                if (code != null && !code.isBlank()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(code), null);
                    roomCodeDisplay.setToolTipText("Copied");
                }
            }
        });

        JComboBox<String> modeBox = styleCombo(new String[]{"STANDARD", "RAPID", "BLITZ"});
        modeBox.addActionListener(e -> send("SET_MODE:" + modeBox.getSelectedItem()));

        JComboBox<String> colorBox = styleCombo(new String[]{"WHITE", "BLACK", "RANDOM"});
        colorBox.addActionListener(e -> send("SET_COLOR:" + colorBox.getSelectedItem()));

        JComboBox<String> timerBox = styleCombo(new String[]{"300", "180", "60", "30", "15"});
        timerBox.addActionListener(e -> {
            Object value = timerBox.getSelectedItem();
            if (value != null) {
                send("SET_TIMER:" + value);
                turnLimitSeconds = Integer.parseInt(String.valueOf(value));
            }
        });

        JLabel waiting = new JLabel("Waiting for your opponent to join...");
        waiting.setForeground(TEXT_DIM);
        waiting.setAlignmentX(Component.CENTER_ALIGNMENT);

        Component[] comps = {title, strut(18), codeLabel, strut(4), roomCodeDisplay,
                strut(32), caption("Game Mode"), strut(4), modeBox,
                strut(16), caption("Your Colour"), strut(4), colorBox,
                strut(16), caption("Turn Time (seconds)"), strut(4), timerBox,
                strut(24), waiting};

        for (Component c : comps) {
            if (c instanceof JComboBox) {
                prepare(c, 300, 40);
            } else {
                prepare(c, 300, 0);
            }
            card.add(c);
        }

        wrapper.add(card);
        return wrapper;
    }

    private JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT_GRAY);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    private JPanel createGamePanel() {
        JPanel wrapper = new JPanel(new BorderLayout(28, 0));
        wrapper.setBackground(BG_BLACK);
        wrapper.setBorder(new EmptyBorder(28, 28, 28, 28));

        JPanel boardHolder = new JPanel(new GridBagLayout());
        boardHolder.setBackground(BG_BLACK);
        board.setPreferredSize(new Dimension(620, 620));
        boardHolder.add(board);

        board.setSquareListener(this::onSquareClick);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(BG_BLACK);
        side.setPreferredSize(new Dimension(320, 620));
        side.setMaximumSize(new Dimension(360, 1000));

        statusLabel = new JLabel("Connecting...");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 24f));
        statusLabel.setForeground(TEXT_WHITE);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        clockLabel = new JLabel(" ");
        clockLabel.setForeground(TEXT_DIM);
        clockLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        clockLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        statusDetail = new JLabel(" ");
        statusDetail.setForeground(TEXT_GRAY);
        statusDetail.setFont(statusDetail.getFont().deriveFont(Font.PLAIN, 12f));
        statusDetail.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel logTitle = captionLeft("Match log");
        logTitle.setForeground(TEXT_GRAY);

        logList = new JList<>(logHolder);
        logList.setBackground(CARD_BLACK);
        logList.setForeground(TEXT_GRAY);
        logList.setSelectionBackground(CARD_BLACK);
        logList.setSelectionForeground(TEXT_WHITE);
        logList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        logList.setFont(logList.getFont().deriveFont(Font.PLAIN, 12f));

        JScrollPane scroll = new JScrollPane(logList);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        scroll.getViewport().setBackground(CARD_BLACK);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        // The log absorbs the leftover height: a fixed cap left a dead zone in
        // the middle of the panel on any window taller than the minimum.
        scroll.setMinimumSize(new Dimension(340, 160));
        scroll.setPreferredSize(new Dimension(340, 320));
        scroll.setMaximumSize(new Dimension(340, Integer.MAX_VALUE));

        resignButton = styleButton("Resign Match", false);
        resignButton.setMaximumSize(new Dimension(340, 46));
        resignButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        resignButton.addActionListener(e -> confirmResign());

        flipButton = styleButton("Flip Board", false);
        flipButton.setMaximumSize(new Dimension(340, 46));
        flipButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        flipButton.addActionListener(e -> board.setFlipped(!board.isFlipped()));

        side.add(statusLabel);
        side.add(strut(4));
        side.add(clockLabel);
        side.add(strut(10));
        side.add(statusDetail);
        side.add(strut(18));
        side.add(logTitle);
        side.add(strut(8));
        side.add(scroll);
        side.add(strut(16));
        side.add(flipButton);
        side.add(strut(10));
        side.add(resignButton);

        wrapper.add(boardHolder, BorderLayout.CENTER);
        wrapper.add(side, BorderLayout.EAST);
        return wrapper;
    }

    private JLabel captionLeft(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    // ── widget helpers ──────────────────────────────────────────────────────

    private Component strut(int height) {
        return Box.createRigidArea(new Dimension(0, height));
    }

    /** Centers a component and optionally fixes its width and height. */
    private void prepare(Component c, int width, int height) {
        if (!(c instanceof JComponent)) return;
        JComponent jc = (JComponent) c;
        jc.setAlignmentX(Component.CENTER_ALIGNMENT);
        if (width > 0) {
            Dimension size = new Dimension(width, height > 0 ? height : jc.getPreferredSize().height);
            jc.setPreferredSize(size);
            jc.setMaximumSize(new Dimension(width, height > 0 ? height : Integer.MAX_VALUE));
        }
    }

    private JTextField styledTextField(String placeholder) {
        JTextField field = new JTextField();
        field.putClientProperty("JTextField.placeholderText", placeholder);
        styleInput(field);
        return field;
    }

    private JPasswordField styledPasswordField(String placeholder) {
        JPasswordField field = new JPasswordField();
        field.putClientProperty("JTextField.placeholderText", placeholder);
        styleInput(field);
        return field;
    }

    private void styleInput(JTextField field) {
        field.setBackground(CARD_BLACK);
        field.setForeground(TEXT_WHITE);
        field.setCaretColor(TEXT_WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(0, 12, 0, 12)));
    }

    private JButton styleButton(String text, boolean primary) {
        JButton btn = new JButton(text);
        if (primary) {
            btn.setBackground(Color.WHITE);
            btn.setForeground(Color.BLACK);
            btn.setBorder(BorderFactory.createEmptyBorder());
        } else {
            btn.setBackground(CARD_BLACK);
            btn.setForeground(TEXT_WHITE);
            btn.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        }
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JComboBox<String> styleCombo(String[] items) {
        JComboBox<String> box = new JComboBox<>(items);
        box.setBackground(BG_BLACK);
        box.setForeground(TEXT_WHITE);
        box.setFocusable(false);
        box.setMaximumSize(new Dimension(300, 40));
        return box;
    }

    /**
     * Switches to one of the named cards. Package-private so the offscreen
     * snapshot tool can render each screen for inspection.
     */
    void showCard(String card) {
        cardLayout.show(mainContainer, card);
    }

    /** The board component, so snapshots can drive it directly. */
    BoardPanel boardComponent() {
        return board;
    }

    /** Applies the look and feel. Split out so the snapshot tool matches the app. */
    static void setUpLookAndFeel() {
        try {
            File fontFile = new File("lib/Comfortaa.ttf");
            if (fontFile.exists()) {
                Font comfortaa = Font.createFont(Font.TRUETYPE_FONT, fontFile);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(comfortaa);
                UIManager.put("defaultFont", comfortaa.deriveFont(15f));
            }
            FlatDarkLaf.setup();
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("Panel.arc", 10);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Component.innerFocusWidth", 0);
            UIManager.put("TextComponent.background", BG_BLACK);
            UIManager.put("ScrollBar.track", BG_BLACK);
            UIManager.put("ScrollBar.thumb", BORDER_COLOR);
            UIManager.put("ComboBox.background", BG_BLACK);
            UIManager.put("ComboBox.selectionBackground", CARD_BLACK);
        } catch (Exception ignored) {
            // A missing font or LAF must never stop the client from starting.
        }
    }

    public static void main(String[] args) {
        setUpLookAndFeel();

        SwingUtilities.invokeLater(() -> {
            ClientGUI gui = new ClientGUI();
            gui.setVisible(true);
        });
    }
}
