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
    
    // "Nero vivace" (Deep rich blue-gray)
    private static final Color BG_COLOR = new Color(24, 24, 36);
    private static final Color CARD_COLOR = new Color(36, 36, 50);
    private static final Color ACCENT_COLOR = new Color(94, 129, 172);
    private static final Color BOARD_LIGHT = new Color(229, 233, 240);
    private static final Color BOARD_DARK = new Color(136, 154, 180);
    
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
        super("Chess Multiplayer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 750);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_COLOR);
        
        initUnicodeMap();
        
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        mainContainer.setBackground(BG_COLOR);
        
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
                if (welcome == null) throw new IOException("Server returned null on welcome");
                
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
        JOptionPane.showMessageDialog(this, msg, "Information", JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void send(String cmd) {
        if (out != null) {
            out.println(cmd);
        } else {
            showErrorPopup("Not connected to server!");
        }
    }
    
    private void logEvent(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (logModel != null) {
                logModel.add(0, msg);
            }
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
                    setTitle("Chess - Playing as " + myColor);
                    logEvent("You are playing as " + myColor);
                } else if (line.startsWith("BOARD:")) {
                    updateBoardFromNetwork();
                } else if (line.startsWith("MOVE_OK:")) {
                    logEvent("Moved: " + line.substring(8));
                } else if (line.startsWith("MOVE_ERROR:")) {
                    logEvent("Error: " + line.substring(11));
                    showErrorPopup(line.substring(11));
                } else if (line.startsWith("GAME_OVER:")) {
                    String reason = line.substring(10);
                    logEvent("Game Over! " + reason);
                    statusLabel.setText("GAME OVER");
                    JOptionPane.showMessageDialog(this, reason, "Game Over", JOptionPane.INFORMATION_MESSAGE);
                    cardLayout.show(mainContainer, "LOBBY");
                } else if ("PING".equals(line)) {
                    send("PONG");
                } else if (line.startsWith("INFO:")) {
                    logEvent(line.substring(5));
                }
            } catch (Exception ex) {
                showErrorPopup("Error processing server message: " + ex.getMessage());
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
                        btn.setForeground(Character.isUpperCase(p.charAt(0)) ? Color.WHITE : Color.BLACK);
                    }
                }
            }
            // Read coordinate line
            in.readLine();
            // Read Current Turn line
            String turnLine = in.readLine();
            if (turnLine != null && turnLine.contains("Current Turn:")) {
                String currentTurn = turnLine.split(":")[1].trim();
                boolean isMyTurn = currentTurn.equalsIgnoreCase(myColor);
                if (isMyTurn) {
                    statusLabel.setText("IT'S YOUR TURN");
                    statusLabel.setForeground(new Color(163, 190, 140)); // green
                } else {
                    statusLabel.setText("OPPONENT'S TURN");
                    statusLabel.setForeground(new Color(235, 203, 139)); // yellow
                }
            }
        } catch (Exception e) {
            logEvent("Error reading board.");
        }
    }

    private JPanel createAuthPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(BG_COLOR);
        
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_COLOR);
        card.setBorder(new EmptyBorder(50, 50, 50, 50));
        card.putClientProperty("FlatLaf.styleClass", "card");
        
        JLabel title = new JLabel("Chess Multiplayer");
        title.setFont(title.getFont().deriveFont(28f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JTextField userField = new JTextField();
        userField.putClientProperty("JTextField.placeholderText", "Username");
        
        JPasswordField passField = new JPasswordField();
        passField.putClientProperty("JTextField.placeholderText", "Password");
        
        JButton btnLogin = new JButton("Login");
        btnLogin.setBackground(ACCENT_COLOR);
        btnLogin.setForeground(Color.WHITE);
        btnLogin.addActionListener(e -> send("LOGIN:" + userField.getText() + ":" + new String(passField.getPassword())));
        
        JButton btnGuest = new JButton("Play as Guest");
        btnGuest.addActionListener(e -> send("GUEST:Guest_" + (int)(Math.random()*1000)));
        
        // Ensure strictly sized & centered elements
        Component[] comps = {title, Box.createRigidArea(new Dimension(0,40)), userField, 
                             Box.createRigidArea(new Dimension(0,15)), passField, 
                             Box.createRigidArea(new Dimension(0,30)), btnLogin, 
                             Box.createRigidArea(new Dimension(0,15)), btnGuest};
                             
        for (Component c : comps) {
            if (c instanceof JComponent) {
                ((JComponent) c).setAlignmentX(Component.CENTER_ALIGNMENT);
                if (c instanceof JTextField || c instanceof JButton) {
                    ((JComponent) c).setMaximumSize(new Dimension(260, 45));
                    ((JComponent) c).setPreferredSize(new Dimension(260, 45));
                }
            }
            card.add(c);
        }
        
        wrapper.add(card);
        return wrapper;
    }

    private JPanel createLobbyPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(BG_COLOR);
        
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_COLOR);
        card.setBorder(new EmptyBorder(50, 50, 50, 50));
        card.putClientProperty("FlatLaf.styleClass", "card");
        
        JLabel title = new JLabel("Game Lobby");
        title.setFont(title.getFont().deriveFont(28f));
        
        JButton btnCreate = new JButton("Create New Room");
        btnCreate.setBackground(ACCENT_COLOR);
        btnCreate.setForeground(Color.WHITE);
        btnCreate.addActionListener(e -> send("CREATE_ROOM:Match:false"));
        
        JLabel lbl = new JLabel("Or join with a code:");
        lbl.setForeground(new Color(180, 180, 200));
        
        JTextField codeField = new JTextField();
        codeField.putClientProperty("JTextField.placeholderText", "Room Code (e.g. A1B2C3)");
        
        JButton btnJoin = new JButton("Join Room");
        btnJoin.addActionListener(e -> send("JOIN_ROOM:" + codeField.getText()));
        
        Component[] comps = {title, Box.createRigidArea(new Dimension(0,40)), btnCreate, 
                             Box.createRigidArea(new Dimension(0,40)), lbl,
                             Box.createRigidArea(new Dimension(0,10)), codeField,
                             Box.createRigidArea(new Dimension(0,15)), btnJoin};
                             
        for (Component c : comps) {
            if (c instanceof JComponent) {
                ((JComponent) c).setAlignmentX(Component.CENTER_ALIGNMENT);
                if (c instanceof JTextField || c instanceof JButton) {
                    ((JComponent) c).setMaximumSize(new Dimension(260, 45));
                    ((JComponent) c).setPreferredSize(new Dimension(260, 45));
                }
            }
            card.add(c);
        }
        
        wrapper.add(card);
        return wrapper;
    }

    private JPanel createGamePanel() {
        JPanel wrapper = new JPanel(new BorderLayout(20, 20));
        wrapper.setBackground(BG_COLOR);
        wrapper.setBorder(new EmptyBorder(25, 25, 25, 25));
        
        // Center: Chess Board
        JPanel boardPanel = new JPanel(new GridLayout(8, 8));
        boardPanel.setPreferredSize(new Dimension(600, 600));
        boardPanel.setBackground(BG_COLOR);
        boardPanel.setBorder(BorderFactory.createLineBorder(CARD_COLOR, 6, true));
        
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
        
        // East: Feedback and Logs
        JPanel sidePanel = new JPanel();
        sidePanel.setLayout(new BoxLayout(sidePanel, BoxLayout.Y_AXIS));
        sidePanel.setBackground(BG_COLOR);
        sidePanel.setPreferredSize(new Dimension(250, 600));
        
        statusLabel = new JLabel("Connecting...");
        statusLabel.setFont(statusLabel.getFont().deriveFont(18f));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        logModel = new DefaultListModel<>();
        JList<String> logList = new JList<>(logModel);
        logList.setBackground(CARD_COLOR);
        logList.setForeground(new Color(200, 200, 220));
        logList.setSelectionBackground(CARD_COLOR);
        logList.setSelectionForeground(Color.WHITE);
        
        JScrollPane scrollPane = new JScrollPane(logList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JButton btnResign = new JButton("Resign");
        btnResign.setBackground(new Color(191, 97, 106)); // Soft red
        btnResign.setForeground(Color.WHITE);
        btnResign.setMaximumSize(new Dimension(250, 45));
        btnResign.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnResign.addActionListener(e -> send("RESIGN"));
        
        sidePanel.add(Box.createRigidArea(new Dimension(0, 10)));
        sidePanel.add(statusLabel);
        sidePanel.add(Box.createRigidArea(new Dimension(0, 20)));
        sidePanel.add(scrollPane);
        sidePanel.add(Box.createRigidArea(new Dimension(0, 20)));
        sidePanel.add(btnResign);
        sidePanel.add(Box.createRigidArea(new Dimension(0, 10)));
        
        wrapper.add(boardPanel, BorderLayout.CENTER);
        wrapper.add(sidePanel, BorderLayout.EAST);
        return wrapper;
    }

    private void handleSquareClick(int r, int c) {
        String coord = "" + (char)('a' + c) + (r + 1);
        if (selectedSquare == null) {
            selectedSquare = coord;
            boardSquares[r][c].setBorder(BorderFactory.createLineBorder(new Color(235, 203, 139), 4));
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
            UIManager.put("Button.arc", 20);
            UIManager.put("Component.arc", 20);
            UIManager.put("TextComponent.arc", 20);
            UIManager.put("Panel.arc", 20);
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            ClientGUI gui = new ClientGUI();
            gui.setVisible(true);
        });
    }
}
