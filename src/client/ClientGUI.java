package client;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class ClientGUI extends JFrame {
    
    // VERCEL / APPLE AESTHETIC (cristianrenosto.party)
    private static final Color BG_BLACK = new Color(5, 5, 5);         // #050505
    private static final Color CARD_BLACK = new Color(10, 10, 10);    // #0a0a0a
    private static final Color BORDER_COLOR = new Color(51, 51, 51);  // #333333
    
    private static final Color TEXT_WHITE = Color.WHITE;
    private static final Color TEXT_GRAY = new Color(136, 136, 136);  // #888888
    
    private static final Color BTN_PRIMARY_BG = Color.WHITE;
    private static final Color BTN_PRIMARY_FG = Color.BLACK;
    
    private static final Color BOARD_LIGHT = new Color(30, 30, 30);
    private static final Color BOARD_DARK = new Color(15, 15, 15);
    
    private CardLayout cardLayout;
    private JPanel mainContainer;
    
    // Server state
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String myColor = "";
    private LineListener listener;
    
    // UI Elements for feedback
    private JButton[][] boardSquares = new JButton[8][8];
    private String selectedSquare = null;
    private JLabel statusLabel;
    private DefaultListModel<String> logModel;
    
    private Map<String, String> pieceUnicodeMap = new HashMap<>();

    public ClientGUI() {
        super("Cristian.party Chess");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 750);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_BLACK);
        
        initUnicodeMap();
        
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        mainContainer.setBackground(BG_BLACK);
        
        mainContainer.add(createAuthPanel(), "AUTH");
        mainContainer.add(createLobbyPanel(), "LOBBY");
        mainContainer.add(createGamePanel(), "GAME");
        
        add(mainContainer);
        cardLayout.show(mainContainer, "AUTH");
        
        connectToServer();
    }
    
    private void initUnicodeMap() {
        pieceUnicodeMap.put("P", "♙"); pieceUnicodeMap.put("N", "♘");
        pieceUnicodeMap.put("B", "♗"); pieceUnicodeMap.put("R", "♖");
        pieceUnicodeMap.put("Q", "♕"); pieceUnicodeMap.put("K", "♔");
        pieceUnicodeMap.put("p", "♟"); pieceUnicodeMap.put("n", "♞");
        pieceUnicodeMap.put("b", "♝"); pieceUnicodeMap.put("r", "♜");
        pieceUnicodeMap.put("q", "♛"); pieceUnicodeMap.put("k", "♚");
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                socket = new Socket("127.0.0.1", 6700);
                socket.setSoTimeout(0);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                
                String welcome = in.readLine();
                if (welcome == null) throw new IOException("Server disconnected");
                
                listener = new LineListener(in, this::handleServerLine, () -> {
                    SwingUtilities.invokeLater(() -> {
                        showErrorPopup("Disconnected from server");
                        System.exit(0);
                    });
                });
                listener.start("GUI-Listener");
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    showErrorPopup("Could not connect to server: " + e.getMessage());
                });
            }
        }).start();
    }
    
    private void showErrorPopup(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Message", JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void send(String cmd) {
        if (out != null) out.println(cmd);
    }
    
    private void logEvent(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (logModel != null) logModel.add(0, msg);
        });
    }

    private void handleServerLine(String line) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (line.startsWith("AUTH_OK:")) {
                    cardLayout.show(mainContainer, "LOBBY");
                } else if (line.startsWith("AUTH_ERROR:")) {
                    showErrorPopup(line.substring(11));
                } else if (line.startsWith("ROOM_CREATED:") || line.startsWith("JOIN_OK:")) {
                    cardLayout.show(mainContainer, "GAME");
                    statusLabel.setText("Waiting for opponent...");
                    logModel.clear();
                    logEvent("Joined room!");
                } else if (line.startsWith("ASSIGNED_COLOR:")) {
                    myColor = line.substring(15).trim();
                    logEvent("Playing as " + myColor);
                } else if (line.startsWith("BOARD:")) {
                    updateBoardFromNetwork();
                } else if (line.startsWith("MOVE_OK:")) {
                    logEvent("Moved: " + line.substring(8));
                } else if (line.startsWith("MOVE_ERROR:")) {
                    showErrorPopup(line.substring(11));
                } else if (line.startsWith("GAME_OVER:")) {
                    String reason = line.substring(10);
                    logEvent("Game Over! " + reason);
                    statusLabel.setText("GAME OVER");
                    JOptionPane.showMessageDialog(this, reason);
                    cardLayout.show(mainContainer, "LOBBY");
                } else if ("PING".equals(line)) {
                    send("PONG");
                } else if (line.startsWith("INFO:")) {
                    logEvent(line.substring(5));
                }
            } catch (Exception ex) {
                showErrorPopup("Error: " + ex.getMessage());
            }
        });
    }
    
    private void updateBoardFromNetwork() {
        try {
            for (int r = 0; r < 8; r++) {
                String row = in.readLine().substring(2); 
                String[] pieces = row.trim().split("\\s+");
                for (int c = 0; c < 8; c++) {
                    String p = pieces[c];
                    JButton btn = boardSquares[7 - r][c];
                    if (p.equals(".")) {
                        btn.setText("");
                    } else {
                        btn.setText(pieceUnicodeMap.getOrDefault(p, p));
                        btn.setForeground(Character.isUpperCase(p.charAt(0)) ? Color.WHITE : new Color(140, 140, 140));
                    }
                }
            }
            in.readLine();
            String turnLine = in.readLine();
            if (turnLine != null && turnLine.contains("Current Turn:")) {
                String currentTurn = turnLine.split(":")[1].trim();
                boolean isMyTurn = currentTurn.equalsIgnoreCase(myColor);
                if (isMyTurn) {
                    statusLabel.setText("Your Turn");
                    statusLabel.setForeground(Color.WHITE);
                } else {
                    statusLabel.setText("Opponent's Turn");
                    statusLabel.setForeground(TEXT_GRAY);
                }
            }
        } catch (Exception e) {}
    }

    private JPanel createAuthPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(BG_BLACK);
        
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BLACK);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(60, 60, 60, 60)
        ));
        
        JLabel title = new JLabel("Chess.");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 36f));
        title.setForeground(TEXT_WHITE);
        
        JLabel subtitle = new JLabel("Minimalist, straight-to-the-point.");
        subtitle.setForeground(TEXT_GRAY);
        
        JTextField userField = new JTextField();
        userField.putClientProperty("JTextField.placeholderText", "Username");
        
        JPasswordField passField = new JPasswordField();
        passField.putClientProperty("JTextField.placeholderText", "Password");
        
        JButton btnLogin = new JButton("Sign In");
        btnLogin.setBackground(BTN_PRIMARY_BG);
        btnLogin.setForeground(BTN_PRIMARY_FG);
        btnLogin.addActionListener(e -> send("LOGIN:" + userField.getText() + ":" + new String(passField.getPassword())));
        
        JButton btnGuest = new JButton("Play as Guest");
        btnGuest.setBackground(CARD_BLACK);
        btnGuest.setForeground(TEXT_WHITE);
        btnGuest.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        btnGuest.addActionListener(e -> send("GUEST:Guest_" + (int)(Math.random()*1000)));
        
        Component[] comps = {title, Box.createRigidArea(new Dimension(0,10)), subtitle,
                             Box.createRigidArea(new Dimension(0,40)), userField, 
                             Box.createRigidArea(new Dimension(0,15)), passField, 
                             Box.createRigidArea(new Dimension(0,30)), btnLogin, 
                             Box.createRigidArea(new Dimension(0,15)), btnGuest};
                             
        for (Component c : comps) {
            if (c instanceof JComponent) {
                ((JComponent) c).setAlignmentX(Component.CENTER_ALIGNMENT);
                if (c instanceof JTextField || c instanceof JButton) {
                    ((JComponent) c).setMaximumSize(new Dimension(280, 45));
                    ((JComponent) c).setPreferredSize(new Dimension(280, 45));
                }
            }
            card.add(c);
        }
        
        wrapper.add(card);
        return wrapper;
    }

    private JPanel createLobbyPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(BG_BLACK);
        
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BLACK);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
            new EmptyBorder(60, 60, 60, 60)
        ));
        
        JLabel title = new JLabel("Lobby");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 32f));
        title.setForeground(TEXT_WHITE);
        
        JButton btnCreate = new JButton("Create Match");
        btnCreate.setBackground(BTN_PRIMARY_BG);
        btnCreate.setForeground(BTN_PRIMARY_FG);
        btnCreate.addActionListener(e -> send("CREATE_ROOM:Match:false"));
        
        JLabel lbl = new JLabel("Or join with code");
        lbl.setForeground(TEXT_GRAY);
        
        JTextField codeField = new JTextField();
        codeField.putClientProperty("JTextField.placeholderText", "Room Code");
        
        JButton btnJoin = new JButton("Join Match");
        btnJoin.setBackground(CARD_BLACK);
        btnJoin.setForeground(TEXT_WHITE);
        btnJoin.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        btnJoin.addActionListener(e -> send("JOIN_ROOM:" + codeField.getText()));
        
        Component[] comps = {title, Box.createRigidArea(new Dimension(0,40)), btnCreate, 
                             Box.createRigidArea(new Dimension(0,40)), lbl,
                             Box.createRigidArea(new Dimension(0,10)), codeField,
                             Box.createRigidArea(new Dimension(0,15)), btnJoin};
                             
        for (Component c : comps) {
            if (c instanceof JComponent) {
                ((JComponent) c).setAlignmentX(Component.CENTER_ALIGNMENT);
                if (c instanceof JTextField || c instanceof JButton) {
                    ((JComponent) c).setMaximumSize(new Dimension(280, 45));
                    ((JComponent) c).setPreferredSize(new Dimension(280, 45));
                }
            }
            card.add(c);
        }
        
        wrapper.add(card);
        return wrapper;
    }

    private JPanel createGamePanel() {
        JPanel wrapper = new JPanel(new BorderLayout(40, 0));
        wrapper.setBackground(BG_BLACK);
        wrapper.setBorder(new EmptyBorder(40, 40, 40, 40));
        
        JPanel boardPanel = new JPanel(new GridLayout(8, 8));
        boardPanel.setPreferredSize(new Dimension(600, 600));
        boardPanel.setBackground(CARD_BLACK);
        boardPanel.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        
        for (int r = 7; r >= 0; r--) {
            for (int c = 0; c < 8; c++) {
                JButton btn = new JButton();
                btn.setFont(new Font("SansSerif", Font.PLAIN, 46));
                btn.setFocusPainted(false);
                btn.setBorderPainted(false);
                btn.setOpaque(true);
                btn.setBackground((r + c) % 2 != 0 ? BOARD_LIGHT : BOARD_DARK);
                btn.putClientProperty("JButton.buttonType", "square");
                
                final int finalR = r;
                final int finalC = c;
                btn.addActionListener(e -> handleSquareClick(finalR, finalC));
                
                boardSquares[r][c] = btn;
                boardPanel.add(btn);
            }
        }
        
        JPanel sidePanel = new JPanel();
        sidePanel.setLayout(new BoxLayout(sidePanel, BoxLayout.Y_AXIS));
        sidePanel.setBackground(BG_BLACK);
        sidePanel.setPreferredSize(new Dimension(300, 600));
        
        statusLabel = new JLabel("Connecting...");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 24f));
        statusLabel.setForeground(TEXT_WHITE);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        JLabel subtitle = new JLabel("Live match event log");
        subtitle.setForeground(TEXT_GRAY);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        logModel = new DefaultListModel<>();
        JList<String> logList = new JList<>(logModel);
        logList.setBackground(CARD_BLACK);
        logList.setForeground(TEXT_GRAY);
        logList.setSelectionBackground(CARD_BLACK);
        logList.setSelectionForeground(TEXT_WHITE);
        
        JScrollPane scrollPane = new JScrollPane(logList);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        JButton btnResign = new JButton("Resign Match");
        btnResign.setBackground(CARD_BLACK);
        btnResign.setForeground(TEXT_WHITE);
        btnResign.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));
        btnResign.setMaximumSize(new Dimension(300, 45));
        btnResign.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnResign.addActionListener(e -> send("RESIGN"));
        
        sidePanel.add(statusLabel);
        sidePanel.add(Box.createRigidArea(new Dimension(0, 5)));
        sidePanel.add(subtitle);
        sidePanel.add(Box.createRigidArea(new Dimension(0, 20)));
        sidePanel.add(scrollPane);
        sidePanel.add(Box.createRigidArea(new Dimension(0, 20)));
        sidePanel.add(btnResign);
        
        wrapper.add(boardPanel, BorderLayout.CENTER);
        wrapper.add(sidePanel, BorderLayout.EAST);
        return wrapper;
    }

    private void handleSquareClick(int r, int c) {
        String coord = "" + (char)('a' + c) + (r + 1);
        if (selectedSquare == null) {
            selectedSquare = coord;
            boardSquares[r][c].setBorder(BorderFactory.createLineBorder(TEXT_WHITE, 3));
            boardSquares[r][c].setBorderPainted(true);
        } else {
            send("MOVE:" + selectedSquare + ":" + coord);
            for(int i=0; i<8; i++) {
                for(int j=0; j<8; j++) {
                    boardSquares[i][j].setBorderPainted(false);
                }
            }
            selectedSquare = null;
        }
    }

    public static void main(String[] args) {
        try {
            File fontFile = new File("lib/Comfortaa.ttf");
            if (fontFile.exists()) {
                Font comfortaa = Font.createFont(Font.TRUETYPE_FONT, fontFile);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(comfortaa);
                UIManager.put("defaultFont", comfortaa.deriveFont(15f));
            }
            FlatDarkLaf.setup();
            UIManager.put("Button.arc", 12);
            UIManager.put("Component.arc", 12);
            UIManager.put("TextComponent.arc", 12);
            UIManager.put("Panel.arc", 12);
            // Vercel style overrides
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Component.innerFocusWidth", 0);
            UIManager.put("TextComponent.background", BG_BLACK);
            UIManager.put("ScrollBar.track", BG_BLACK);
            UIManager.put("ScrollBar.thumb", BORDER_COLOR);
        } catch (Exception e) {}

        SwingUtilities.invokeLater(() -> {
            ClientGUI gui = new ClientGUI();
            gui.setVisible(true);
        });
    }
}
